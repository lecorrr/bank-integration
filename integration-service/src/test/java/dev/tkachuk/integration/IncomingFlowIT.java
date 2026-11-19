package dev.tkachuk.integration;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS008;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS008_DLQ;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS028;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS028_DLQ;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS002;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tkachuk.integration.incoming.IncomingTransferDecided;
import dev.tkachuk.integration.incoming.IncomingTransferReceived;
import dev.tkachuk.integration.incoming.TestPayments;
import dev.tkachuk.interbank.InterbankTopology;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@ServiceIntegrationTest
class IncomingFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration QUIET = Duration.ofSeconds(3);
    private static final String DEAD_LETTER_TOPIC = IncomingTransferDecided.TOPIC + ".DLT";

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private TestScreeningService screening;

    private TestKafka bankCore;
    private TestRabbit partnerBank;

    @BeforeEach
    void declareTheTopologyAndEmptyTheQueues() {
        bankCore = new TestKafka(kafka, TIMEOUT);
        partnerBank = new TestRabbit(rabbit);
        admin.initialize();
        for (String queue : InterbankTopology.queues()) {
            admin.purgeQueue(queue, false);
        }
    }

    @AfterEach
    void putTheScreeningServiceBack() {
        screening.reset();
    }

    @Test
    void shouldAnswerAcscWhenBankCoreCreditsPayment() throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);

        Map<?, ?> transfer = takeTransfer(payment);
        assertThat(transfer.get("toIban")).isEqualTo(TestPayments.OUR_CUSTOMER);
        assertThat(transfer.get("fromName")).isEqualTo("Olena Kovalenko");
        assertThat(new BigDecimal(String.valueOf(transfer.get("amount")))).isEqualByComparingTo("250.00");
        decide(payment, UUID.randomUUID(), "CREDITED", null);

        Pacs002 answer = takeAnswer(payment.messageId());
        assertThat(answer.status()).isEqualTo(TransactionStatus.ACSC);
        assertThat(answer.reasonCode()).isNull();
        assertThat(answer.originalUetr()).isEqualTo(payment.uetr());
    }

    @ParameterizedTest(name = "{0} is answered {1}")
    @CsvSource({"UNKNOWN_ACCOUNT, AC01", "CURRENCY_MISMATCH, CURR", "ACCOUNT_FROZEN, MS03"})
    void shouldAnswerRjctWithIsoReasonWhenBankCoreRejects(String reason, String reasonCode)
            throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);
        takeTransfer(payment);

        decide(payment, UUID.randomUUID(), "REJECTED", reason);

        Pacs002 answer = takeAnswer(payment.messageId());
        assertThat(answer.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(answer.reasonCode()).isEqualTo(reasonCode);
    }

    @Test
    void shouldRejectInvalidPaymentWithoutBankCore() {
        Pacs008 payment = TestPayments.another().currency("USD").build();
        send(payment);

        Pacs002 answer = takeAnswer(payment.messageId());
        assertThat(answer.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(answer.reasonCode()).isEqualTo(StatusReason.NOT_ALLOWED_CURRENCY);
        assertThat(transfersWithin(payment, 1, QUIET)).as("a transfer for bank-core").isEmpty();
    }

    @Test
    void shouldRejectSanctionedPayerWithRr04() {
        Pacs008 payment = TestPayments.another().debtorName(TestScreeningService.LISTED_NAME).build();
        send(payment);

        Pacs002 answer = takeAnswer(payment.messageId());
        assertThat(answer.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(answer.reasonCode()).isEqualTo(StatusReason.REGULATORY_REASON);
        send(payment);
        assertThat(takeAnswer(payment.messageId()).messageId()).as("the answer to a copy")
                .isEqualTo(answer.messageId());
        assertThat(transfersWithin(payment, 1, QUIET)).as("a transfer for bank-core").isEmpty();
    }

    @Test
    void shouldHoldPaymentWhileScreeningIsDown() throws Exception {
        screening.goDown();
        Pacs008 payment = TestPayments.another().build();
        send(payment);

        await().atMost(TIMEOUT).until(() -> screening.breakerState() == CircuitBreaker.State.OPEN);
        assertThat(answerTo(ask(payment)).status()).as("the answer while it waits").isEqualTo(TransactionStatus.PDNG);
        assertThat(transfersWithin(payment, 1, QUIET)).as("a transfer for bank-core").isEmpty();
        screening.comeBack();

        takeTransfer(payment);
        decide(payment, UUID.randomUUID(), "CREDITED", null);
        assertThat(takeAnswer(payment.messageId()).status()).isEqualTo(TransactionStatus.ACSC);
    }

    @Test
    void shouldMovePaymentToDlqWhenScreeningRefusesRequest() {
        screening.refuseEverything();
        Pacs008 payment = TestPayments.another().build();
        send(payment);

        Message dead = partnerBank.deadLetter(BANK_PACS008_DLQ, TIMEOUT);
        assertThat(TestRabbit.bodyOf(dead)).isEqualTo(payment.toXml());
        assertThat(TestRabbit.reasonOf(dead)).contains(payment.uetr()).contains("refused");
        assertThat(screening.breakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldRepeatAnswerForDuplicateOfAnsweredPayment() throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);
        takeTransfer(payment);
        decide(payment, UUID.randomUUID(), "CREDITED", null);
        Pacs002 first = takeAnswer(payment.messageId());

        send(payment);

        assertThat(takeAnswer(payment.messageId())).as("the same pacs.002, MsgId and all").isEqualTo(first);
        assertThat(transfersWithin(payment, 2, QUIET)).hasSize(1);
    }

    @Test
    void shouldForwardDuplicateAgainWhileUndecided() throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);
        send(payment);

        assertThat(transfersWithin(payment, 2, TIMEOUT)).as("the payment, twice, for bank-core").hasSize(2);
        decide(payment, UUID.randomUUID(), "CREDITED", null);
        assertThat(takeAnswer(payment.messageId()).status()).isEqualTo(TransactionStatus.ACSC);
    }

    @Test
    void shouldRejectSecondMessageForSamePaymentWithAm05() throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);
        takeTransfer(payment);
        Pacs008 copy = TestPayments.builder().uetr(payment.uetr()).messageId(newMessageId()).build();

        send(copy);

        Pacs002 refusal = takeAnswer(copy.messageId());
        assertThat(refusal.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(refusal.reasonCode()).isEqualTo(StatusReason.DUPLICATION);
        assertThat(answerTo(ask(copy)).reasonCode()).as("asked about the copy").isEqualTo(StatusReason.DUPLICATION);
        decide(payment, UUID.randomUUID(), "CREDITED", null);
        assertThat(takeAnswer(payment.messageId()).status()).isEqualTo(TransactionStatus.ACSC);
    }

    @Test
    void shouldAnswerStatusRequestPendingUntilBankCoreDecides() throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);
        takeTransfer(payment);

        assertThat(answerTo(ask(payment)).status()).isEqualTo(TransactionStatus.PDNG);
        decide(payment, UUID.randomUUID(), "CREDITED", null);
        Pacs002 first = takeAnswer(payment.messageId());

        Pacs002 again = answerTo(ask(payment));
        assertThat(again.status()).isEqualTo(TransactionStatus.ACSC);
        assertThat(again.messageId()).isNotEqualTo(first.messageId());
    }

    @Test
    void shouldAnswerAg09ForUnknownPayment() {
        Pacs008 neverSent = TestPayments.another().build();

        Pacs002 answer = answerTo(ask(neverSent));

        assertThat(answer.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(answer.reasonCode()).isEqualTo(StatusReason.PAYMENT_NOT_RECEIVED);
    }

    @Test
    void shouldMoveUnreadablePaymentToDlq() {
        rabbit.convertAndSend(EXCHANGE, BANK_PACS008, "<Document/>");

        Message dead = partnerBank.deadLetter(BANK_PACS008_DLQ, TIMEOUT);
        assertThat(TestRabbit.bodyOf(dead)).isEqualTo("<Document/>");
        assertThat(TestRabbit.reasonOf(dead)).contains(Pacs008.NAMESPACE);
    }

    @Test
    void shouldMovePaymentReusingMessageIdToDlq() throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);
        takeTransfer(payment);
        Pacs008 reusingItsId = TestPayments.another().messageId(payment.messageId()).build();

        send(reusingItsId);

        Message dead = partnerBank.deadLetter(BANK_PACS008_DLQ, TIMEOUT);
        assertThat(TestRabbit.reasonOf(dead)).contains(reusingItsId.uetr()).contains("reuses the MsgId");
    }

    @Test
    void shouldMoveStatusRequestFromUnknownBankToDlq() {
        Pacs008 payment = TestPayments.another().debtorAgentBic("UNKNUAUKXXX").build();

        ask(payment);

        Message dead = partnerBank.deadLetter(BANK_PACS028_DLQ, TIMEOUT);
        assertThat(TestRabbit.reasonOf(dead)).contains("UNKNUAUKXXX").contains("not in the directory");
    }

    @Test
    void shouldMoveDecisionForUnknownPaymentToDltAndContinue()
            throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);
        takeTransfer(payment);
        String unknownPayment = UUID.randomUUID().toString();
        String aboutNoPayment = decision(unknownPayment, UUID.randomUUID(), "CREDITED", null);
        bankCore.publish(IncomingTransferDecided.TOPIC, payment.uetr(), aboutNoPayment);

        decide(payment, UUID.randomUUID(), "CREDITED", null);

        assertThat(takeAnswer(payment.messageId()).status()).isEqualTo(TransactionStatus.ACSC);
        ConsumerRecord<String, String> deadLetter =
                bankCore.records(DEAD_LETTER_TOPIC, TestKafka.keyed(payment.uetr()), 1).getFirst();
        assertThat(deadLetter.value()).isEqualTo(aboutNoPayment);
        assertThat(TestKafka.headerOf(deadLetter, "dead-letter-reason")).contains(unknownPayment)
                .contains("never received");
    }

    @Test
    void shouldMoveConflictingDecisionToDltAndKeepAnswer() throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);
        takeTransfer(payment);
        UUID transferId = UUID.randomUUID();
        decide(payment, transferId, "CREDITED", null);
        Pacs002 answer = takeAnswer(payment.messageId());

        decide(payment, transferId, "REJECTED", "UNKNOWN_ACCOUNT");

        ConsumerRecord<String, String> deadLetter =
                bankCore.records(DEAD_LETTER_TOPIC, TestKafka.keyed(payment.uetr()), 1).getFirst();
        assertThat(TestKafka.headerOf(deadLetter, "dead-letter-reason")).contains("conflicts with our answer ACSC");
        assertThat(answerTo(ask(payment)).status()).isEqualTo(answer.status());
    }

    @Test
    void shouldResendSameAnswerForRepeatedDecision() throws Exception {
        Pacs008 payment = TestPayments.another().build();
        send(payment);
        takeTransfer(payment);
        UUID transferId = UUID.randomUUID();
        decide(payment, transferId, "REJECTED", "UNKNOWN_ACCOUNT");
        Pacs002 first = takeAnswer(payment.messageId());

        decide(payment, transferId, "REJECTED", "UNKNOWN_ACCOUNT");

        assertThat(takeAnswer(payment.messageId())).isEqualTo(first);
    }

    private void send(Pacs008 payment) {
        rabbit.convertAndSend(EXCHANGE, BANK_PACS008, payment.toXml());
    }

    private Pacs028 ask(Pacs008 payment) {
        Pacs028 request = Pacs028.about(payment, newMessageId(), now());
        rabbit.convertAndSend(EXCHANGE, BANK_PACS028, request.toXml());
        return request;
    }

    private void decide(Pacs008 payment, UUID transferId, String decision, String reason) throws Exception {
        bankCore.publish(IncomingTransferDecided.TOPIC, payment.uetr(),
                decision(payment.uetr(), transferId, decision, reason));
    }

    private static String decision(String uetr, UUID transferId, String decision, String reason) {
        return """
                {"uetr": "%s", "transferId": "%s", "decision": "%s", "reason": %s,
                 "decidedAt": "2026-11-18T09:15:01Z"}
                """.formatted(uetr, transferId, decision, reason == null ? "null" : "\"" + reason + "\"");
    }

    private Map<?, ?> takeTransfer(Pacs008 payment) {
        List<ConsumerRecord<String, String>> transfers = transfersWithin(payment, 1, TIMEOUT);
        assertThat(transfers).as("the payment for bank-core on " + IncomingTransferReceived.TOPIC).hasSize(1);
        ConsumerRecord<String, String> transfer = transfers.getFirst();
        assertThat(transfer.key()).as("keyed by the payee's account").isEqualTo(payment.creditorIban());
        return new JsonMapper().readValue(transfer.value(), Map.class);
    }

    private List<ConsumerRecord<String, String>> transfersWithin(Pacs008 payment, int count, Duration wait) {
        return bankCore.recordsWithin(IncomingTransferReceived.TOPIC,
                record -> payment.uetr().equals(new JsonMapper().readValue(record.value(), Map.class).get("uetr")),
                count, wait);
    }

    private Pacs002 takeAnswer(String originalMessageId) {
        Pacs002 answer = partnerBank.next(PARTNER_PACS002, TIMEOUT, Pacs002::parse,
                candidate -> candidate.originalMessageId().equals(originalMessageId));
        assertThat(answer).as("a pacs.002 about pacs.008 " + originalMessageId + " on " + PARTNER_PACS002)
                .isNotNull();
        return answer;
    }

    private Pacs002 answerTo(Pacs028 request) {
        return takeAnswer(request.originalMessageId());
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
