package dev.tkachuk.partnerbank;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS008;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS028;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS028;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.interbank.InterbankTopology;
import dev.tkachuk.interbank.iso20022.Camt053;
import dev.tkachuk.interbank.iso20022.CreditDebit;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({RabbitTestcontainer.class, SftpTestcontainer.class})
class PartnerBankSimIT {

    private static final long RECEIVE_TIMEOUT_MS = 10_000;
    private static final long QUIET_MS = 2_000;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestSftp sftp;

    @BeforeEach
    void declareTheTopologyAndEmptyTheQueues() {
        admin.initialize();
        for (String queue : InterbankTopology.queues()) {
            admin.purgeQueue(queue, false);
        }
    }

    @Test
    void shouldAnswerPaymentWithAcsc() {
        Pacs008 payment = TestPayments.pacs008(newMessageId());

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());

        Message answer = takeAnswer();
        Pacs002 report = Pacs002.parse(bodyOf(answer));
        assertThat(report.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(report.originalEndToEndId()).isEqualTo(payment.endToEndId());
        assertThat(report.status()).isEqualTo(TransactionStatus.ACSC);
        assertThat(answer.getMessageProperties().getMessageId()).isEqualTo(report.messageId());
    }

    @Test
    void shouldRejectPaymentToClosedAccount() {
        Pacs008 payment = TestPayments.pacs008(newMessageId(), TestPayments.CLOSED_ACCOUNT);

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());

        Pacs002 report = Pacs002.parse(bodyOf(takeAnswer()));
        assertThat(report.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isEqualTo(StatusReason.CLOSED_ACCOUNT);
    }

    @Test
    void shouldAnswerEveryPaymentOnce() {
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, TestPayments.pacs008(newMessageId()).toXml());
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, TestPayments.pacs008(newMessageId()).toXml());

        Message first = takeAnswer();
        Message second = takeAnswer();

        assertThat(Pacs002.parse(bodyOf(first)).originalMessageId())
                .isNotEqualTo(Pacs002.parse(bodyOf(second)).originalMessageId());
        assertThat(rabbit.receive(BANK_PACS002, QUIET_MS)).as("no third answer").isNull();
    }

    @Test
    void shouldRepeatAnswerForDuplicatePayment() {
        Pacs008 payment = TestPayments.pacs008(newMessageId(), TestPayments.CLOSED_ACCOUNT);

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());

        Pacs002 first = Pacs002.parse(bodyOf(takeAnswer()));
        Pacs002 second = Pacs002.parse(bodyOf(takeAnswer()));
        assertThat(second.messageId()).isNotEqualTo(first.messageId());
        assertThat(second.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(second.reasonCode()).isEqualTo(StatusReason.CLOSED_ACCOUNT);
        assertThat(second.originalMessageId()).isEqualTo(payment.messageId());
    }

    @Test
    void shouldAnswerPendingForSilentAccountWhenAsked() {
        Pacs008 payment = TestPayments.pacs008(newMessageId(), TestPayments.SILENT_ACCOUNT);
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());
        assertThat(rabbit.receive(BANK_PACS002, QUIET_MS)).as("an answer to the payment").isNull();

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS028, statusRequestAbout(payment).toXml());

        Pacs002 report = Pacs002.parse(bodyOf(takeAnswer()));
        assertThat(report.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(report.status()).isEqualTo(TransactionStatus.PDNG);
    }

    @Test
    void shouldAnswerStatusRequestWithPaymentAnswer() {
        Pacs008 payment = TestPayments.pacs008(newMessageId());
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());
        Pacs002 first = Pacs002.parse(bodyOf(takeAnswer()));

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS028, statusRequestAbout(payment).toXml());

        Pacs002 again = Pacs002.parse(bodyOf(takeAnswer()));
        assertThat(again.status()).isEqualTo(TransactionStatus.ACSC);
        assertThat(again.originalUetr()).isEqualTo(payment.uetr());
        assertThat(again.messageId()).isNotEqualTo(first.messageId());
    }

    @Test
    void shouldAnswerAg09ForUnknownPayment() {
        Pacs008 neverSent = TestPayments.pacs008(newMessageId());

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS028, statusRequestAbout(neverSent).toXml());

        Pacs002 report = Pacs002.parse(bodyOf(takeAnswer()));
        assertThat(report.originalMessageId()).isEqualTo(neverSent.messageId());
        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isEqualTo(StatusReason.PAYMENT_NOT_RECEIVED);
    }

    @Test
    void shouldSendPaymentAndKeepAnswer() throws Exception {
        Map<?, ?> sent = sendPayment("""
                {"toIban": "%s", "toName": "Artem Demo", "amount": 250.00, "description": "For the concert tickets"}
                """.formatted(TestPayments.BANK_CORE_CUSTOMER));

        Message message = takeFrom(BANK_PACS008);
        Pacs008 payment = Pacs008.parse(bodyOf(message));
        assertThat(payment.uetr()).isEqualTo(sent.get("uetr"));
        assertThat(payment.messageId()).isEqualTo(sent.get("messageId"));
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(payment.messageId());
        assertThat(payment.debtorIban()).isEqualTo(TestPayments.OPEN_ACCOUNT);
        assertThat(payment.debtorAgentBic()).isEqualTo("PRTBUAUKXXX");
        assertThat(payment.creditorAgentBic()).isEqualTo("BNKCUAUKXXX");
        assertThat(payment.currency()).as("hryvnia when the request names no currency").isEqualTo("UAH");

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS002,
                Pacs002.answering(payment, TransactionStatus.ACSC, newMessageId(), now()).toXml());

        Map<?, ?> answer = firstAnswerTo(payment.uetr());
        assertThat(answer.get("status")).isEqualTo("ACSC");
    }

    @Test
    void shouldSendPacs028ForPayment() throws Exception {
        Map<?, ?> sent = sendPayment(aPaymentToBankCore());
        Pacs008 payment = Pacs008.parse(bodyOf(takeFrom(BANK_PACS008)));

        mvc.perform(post("/api/payments/{uetr}/status-requests", sent.get("uetr")))
                .andExpect(status().isAccepted());

        Pacs028 request = Pacs028.parse(bodyOf(takeFrom(BANK_PACS028)));
        assertThat(request.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(request.originalUetr()).isEqualTo(payment.uetr());
        assertThat(request.instructingAgentBic()).isEqualTo("PRTBUAUKXXX");
        assertThat(request.instructedAgentBic()).isEqualTo("BNKCUAUKXXX");
    }

    @Test
    void shouldResendPaymentOnAg09() throws Exception {
        sendPayment(aPaymentToBankCore());
        String lost = bodyOf(takeFrom(BANK_PACS008));
        Pacs008 payment = Pacs008.parse(lost);

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS002, Pacs002.notReceived(statusRequestAbout(payment),
                newMessageId(), now()).toXml());

        assertThat(bodyOf(takeFrom(BANK_PACS008))).as("the same pacs.008 again").isEqualTo(lost);
    }

    @Test
    void shouldReturnBadRequestForInvalidPayment() throws Exception {
        String withoutAName = """
                {"toIban": "%s", "amount": 250.00, "description": "For the concert tickets"}
                """.formatted(TestPayments.BANK_CORE_CUSTOMER);

        String body = mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(withoutAName))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(String.valueOf(new JsonMapper().readValue(body, Map.class).get("error"))).contains("creditorName");
        assertThat(rabbit.receive(BANK_PACS008, QUIET_MS)).as("a pacs.008").isNull();
    }

    @Test
    void shouldReturnNotFoundForUnknownPayment() throws Exception {
        String unknown = UUID.randomUUID().toString();

        mvc.perform(get("/api/payments/{uetr}", unknown)).andExpect(status().isNotFound());
        mvc.perform(post("/api/payments/{uetr}/status-requests", unknown)).andExpect(status().isNotFound());
    }

    @Test
    void shouldLeaveStatementOfAcceptedPaymentsOnSftp() throws Exception {
        Pacs008 ours = TestPayments.fromBankCore(UUID.randomUUID().toString(), "150.00");
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, ours.toXml());
        takeAnswer();
        sendPayment(aPaymentToBankCore());
        Pacs008 theirs = Pacs008.parse(bodyOf(takeFrom(BANK_PACS008)));
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS002,
                Pacs002.answering(theirs, TransactionStatus.ACSC, newMessageId(), now()).toXml());
        firstAnswerTo(theirs.uetr());

        Map<?, ?> sent = sendStatement("");

        Camt053 statement = Camt053.parse(sftp.read((String) sent.get("fileName")));
        assertThat(statement.messageId()).isEqualTo(sent.get("messageId"));
        assertThat(statement.accountIban()).isEqualTo("UA853888880000000000000000399");
        assertThat(statement.entries()).extracting(entry -> entry.creditDebit() + " " + entry.uetr() + " "
                + entry.amount()).contains("DBIT " + ours.uetr() + " 150.00", "CRDT " + theirs.uetr() + " 250.00");
    }

    @Test
    void shouldPutRequestedFaultsInStatement() throws Exception {
        Pacs008 ours = TestPayments.fromBankCore(UUID.randomUUID().toString(), "150.00");
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, ours.toXml());
        takeAnswer();

        Map<?, ?> sent = sendStatement("""
                {"changeAmounts": {"%s": 149.00}, "addUnknown": [{"direction": "CRDT", "amount": 10.00}]}
                """.formatted(ours.uetr()));

        Camt053 statement = Camt053.parse(sftp.read((String) sent.get("fileName")));
        assertThat(statement.entries()).filteredOn(entry -> entry.uetr().equals(ours.uetr()))
                .extracting(Camt053.Entry::amount).containsExactly(new BigDecimal("149.00"));
        assertThat(statement.entries().getLast().creditDebit()).isEqualTo(CreditDebit.CRDT);
        assertThat(statement.entries().getLast().amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void shouldRefuseFaultForPaymentItDidNotBook() throws Exception {
        String body = mvc.perform(post("/api/statements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveOut\": [\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(String.valueOf(new JsonMapper().readValue(body, Map.class).get("error"))).contains("is not booked");
    }

    private Map<?, ?> sendStatement(String faults) throws Exception {
        String body = mvc.perform(post("/api/statements").contentType(MediaType.APPLICATION_JSON).content(faults))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new JsonMapper().readValue(body, Map.class);
    }

    private static String aPaymentToBankCore() {
        return """
                {"toIban": "%s", "toName": "Artem Demo", "amount": 250.00, "currency": "UAH",
                 "description": "For the concert tickets"}
                """.formatted(TestPayments.BANK_CORE_CUSTOMER);
    }

    private Map<?, ?> sendPayment(String json) throws Exception {
        String body = mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return new JsonMapper().readValue(body, Map.class);
    }

    private Map<?, ?> firstAnswerTo(Object uetr) throws Exception {
        Instant deadline = Instant.now().plusMillis(RECEIVE_TIMEOUT_MS);
        while (Instant.now().isBefore(deadline)) {
            String body = mvc.perform(get("/api/payments/{uetr}", uetr))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<?> answers = (List<?>) new JsonMapper().readValue(body, Map.class).get("answers");
            if (!answers.isEmpty()) {
                return (Map<?, ?>) answers.getFirst();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No answer kept about payment " + uetr + " within " + RECEIVE_TIMEOUT_MS + " ms");
    }

    private Message takeFrom(String queue) {
        Message message = rabbit.receive(queue, RECEIVE_TIMEOUT_MS);
        assertThat(message).as("a message on queue " + queue).isNotNull();
        return message;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private Message takeAnswer() {
        Message answer = rabbit.receive(BANK_PACS002, RECEIVE_TIMEOUT_MS);
        assertThat(answer).as("a pacs.002 on queue " + BANK_PACS002).isNotNull();
        return answer;
    }

    private static Pacs028 statusRequestAbout(Pacs008 payment) {
        return Pacs028.about(payment, newMessageId(), Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String bodyOf(Message message) {
        String encoding = message.getMessageProperties().getContentEncoding();
        return new String(message.getBody(), encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding));
    }
}
