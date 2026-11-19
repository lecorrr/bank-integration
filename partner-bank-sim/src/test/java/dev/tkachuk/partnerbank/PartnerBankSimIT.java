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
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
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

/**
 * The simulator against a real RabbitMQ: the test plays bank-core's bank. As the payee's bank (flow A), it sends
 * pacs.008 and pacs.028 messages and reads the answers; as the payer's bank (flow B), the simulator sends payments
 * through its API, and the test takes them off bank-core's queues and answers them. The simulator remembers every
 * payment for the whole test run, so each test uses its own MsgIds.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RabbitTestcontainer.class)
class PartnerBankSimIT {

    private static final long RECEIVE_TIMEOUT_MS = 10_000;
    /** How long to watch for an answer that must not come. */
    private static final long QUIET_MS = 2_000;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void declareTheTopologyAndEmptyTheQueues() {
        // Declares InterbankTopology now, so no message is published before its queue exists.
        admin.initialize();
        for (String queue : InterbankTopology.queues()) {
            admin.purgeQueue(queue, false);
        }
    }

    @Test
    void answersAPaymentWithAnAcceptedStatusReport() {
        Pacs008 payment = TestPayments.pacs008(newMessageId());

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());

        Message answer = takeAnswer();
        Pacs002 report = Pacs002.parse(bodyOf(answer));
        assertThat(report.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(report.originalEndToEndId()).isEqualTo(payment.endToEndId());
        assertThat(report.status()).isEqualTo(TransactionStatus.ACSC);
        // The answer travels under its own id, not the payment's.
        assertThat(answer.getMessageProperties().getMessageId()).isEqualTo(report.messageId());
    }

    @Test
    void refusesAPaymentToTheClosedAccountInItsConfiguration() {
        Pacs008 payment = TestPayments.pacs008(newMessageId(), TestPayments.CLOSED_ACCOUNT);

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());

        Pacs002 report = Pacs002.parse(bodyOf(takeAnswer()));
        assertThat(report.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isEqualTo(StatusReason.CLOSED_ACCOUNT);
    }

    @Test
    void answersEveryPaymentOnce() {
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, TestPayments.pacs008(newMessageId()).toXml());
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, TestPayments.pacs008(newMessageId()).toXml());

        Message first = takeAnswer();
        Message second = takeAnswer();

        assertThat(Pacs002.parse(bodyOf(first)).originalMessageId())
                .isNotEqualTo(Pacs002.parse(bodyOf(second)).originalMessageId());
        assertThat(rabbit.receive(BANK_PACS002, QUIET_MS)).as("no third answer").isNull();
    }

    @Test
    void answersTheSamePaymentAgainAsBefore() {
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
    void leavesAPaymentToASilentAccountUnansweredAndAnswersPendingWhenAsked() {
        Pacs008 payment = TestPayments.pacs008(newMessageId(), TestPayments.SILENT_ACCOUNT);
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());
        assertThat(rabbit.receive(BANK_PACS002, QUIET_MS)).as("an answer to the payment").isNull();

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS028, statusRequestAbout(payment).toXml());

        Pacs002 report = Pacs002.parse(bodyOf(takeAnswer()));
        assertThat(report.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(report.status()).isEqualTo(TransactionStatus.PDNG);
    }

    @Test
    void answersAStatusRequestWithThePaymentsAnswer() {
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
    void answersAStatusRequestAboutAPaymentItNeverReceivedWithAg09() {
        Pacs008 neverSent = TestPayments.pacs008(newMessageId());

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS028, statusRequestAbout(neverSent).toXml());

        Pacs002 report = Pacs002.parse(bodyOf(takeAnswer()));
        assertThat(report.originalMessageId()).isEqualTo(neverSent.messageId());
        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isEqualTo(StatusReason.PAYMENT_NOT_RECEIVED);
    }

    @Test
    void sendsAPaymentToBankCoresBankAndKeepsTheAnswer() throws Exception {
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
    void asksAboutAPaymentWithAPacs028() throws Exception {
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
    void sendsThePaymentAgainWhenBankCoresBankNeverReceivedIt() throws Exception {
        sendPayment(aPaymentToBankCore());
        // Taken off the queue and dropped: bank-core's bank never sees it.
        String lost = bodyOf(takeFrom(BANK_PACS008));
        Pacs008 payment = Pacs008.parse(lost);

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS002, Pacs002.notReceived(statusRequestAbout(payment),
                newMessageId(), now()).toXml());

        assertThat(bodyOf(takeFrom(BANK_PACS008))).as("the same pacs.008 again").isEqualTo(lost);
    }

    @Test
    void refusesAPaymentThatAPacs008CannotCarryAndSaysWhy() throws Exception {
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
    void knowsNoPaymentItNeverSent() throws Exception {
        String unknown = UUID.randomUUID().toString();

        mvc.perform(get("/api/payments/{uetr}", unknown)).andExpect(status().isNotFound());
        mvc.perform(post("/api/payments/{uetr}/status-requests", unknown)).andExpect(status().isNotFound());
    }

    private static String aPaymentToBankCore() {
        return """
                {"toIban": "%s", "toName": "Artem Demo", "amount": 250.00, "currency": "UAH",
                 "description": "For the concert tickets"}
                """.formatted(TestPayments.BANK_CORE_CUSTOMER);
    }

    /** Sends a payment through the simulator's API, as a person running the demo does. */
    private Map<?, ?> sendPayment(String json) throws Exception {
        String body = mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return new JsonMapper().readValue(body, Map.class);
    }

    /** The first answer the simulator keeps about its payment, once it has one. */
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

    /** Honours the encoding the sender declared; Camel declares the JVM's, which is UTF-8 since Java 18. */
    private static String bodyOf(Message message) {
        String encoding = message.getMessageProperties().getContentEncoding();
        return new String(message.getBody(), encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding));
    }
}
