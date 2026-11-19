package dev.tkachuk.integration;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002_DLQ;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS028;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.integration.outgoing.TransferRequested;
import dev.tkachuk.integration.outgoing.TransferStatusChanged;
import dev.tkachuk.interbank.InterbankTopology;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flow A on our side, against a real PostgreSQL, RabbitMQ and Kafka. The test plays both neighbours: bank-core,
 * which publishes the transfer and reads its status, and the partner bank, which takes the pacs.008 off its
 * queue and answers it, or loses it, or keeps quiet. So this module is tested without bank-core and without
 * partner-bank-sim. Every test ends its payment with a final answer, so no status requests carry over.
 */
@ServiceIntegrationTest
class TransferFlowIT {

    /** Generous: the first test also waits for the service's Kafka consumer to join its group. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** How long to watch for a message that must not come. */
    private static final Duration QUIET = Duration.ofSeconds(3);
    private static final String OUR_CUSTOMER = "UA843999990000000000000000001";
    private static final String PARTNER_CUSTOMER = "UA643888880000000000000000777";
    /** At bank 377777, which is not in interbank.participants. */
    private static final String UNKNOWN_BANKS_CUSTOMER = "UA483777770000000000000000123";
    private static final String DEAD_LETTER_TOPIC = TransferRequested.TOPIC + ".DLT";

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private KafkaContainer kafka;

    private TestKafka bankCore;
    private TestRabbit partnerBank;

    @BeforeEach
    void declareTheTopologyAndEmptyTheQueues() {
        bankCore = new TestKafka(kafka, TIMEOUT);
        partnerBank = new TestRabbit(rabbit);
        // Declares InterbankTopology now, so no message is published before its queue exists.
        admin.initialize();
        for (String queue : InterbankTopology.queues()) {
            admin.purgeQueue(queue, false);
        }
    }

    @Test
    void aTransferGoesOutAsAPacs008AndItsAnswerComesBackAsAStatusEvent() throws Exception {
        UUID transferId = UUID.randomUUID();
        requestTransfer(transferId, PARTNER_CUSTOMER);

        Pacs008 payment = takePacs008(transferId);
        assertThat(payment.creditorAgentBic()).isEqualTo("PRTBUAUKXXX");
        assertThat(payment.amount()).isEqualByComparingTo("150.00");
        answer(Pacs002.answering(payment, TransactionStatus.ACSC, newMessageId(), now()));

        Map<?, ?> status = nextEvent(transferId);
        assertThat(status.get("transferId")).isEqualTo(transferId.toString());
        assertThat(status.get("status")).isEqualTo("SETTLED");
    }

    @Test
    void aRefusalComesBackAsARejectedStatusEvent() throws Exception {
        UUID transferId = UUID.randomUUID();
        requestTransfer(transferId, PARTNER_CUSTOMER);

        Pacs008 payment = takePacs008(transferId);
        Pacs002 refusal = Pacs002.rejecting(payment, StatusReason.CLOSED_ACCOUNT, newMessageId(), now());
        answer(refusal);

        Map<?, ?> status = nextEvent(transferId);
        assertThat(status.get("status")).isEqualTo("REJECTED");
        assertThat(status.get("reportId")).isEqualTo(refusal.messageId());
    }

    @Test
    void aRepeatedEventSendsThePaymentOnce() throws Exception {
        UUID transferId = UUID.randomUUID();
        requestTransfer(transferId, PARTNER_CUSTOMER);
        requestTransfer(transferId, PARTNER_CUSTOMER);

        Pacs008 payment = takePacs008(transferId);
        assertThat(pacs008About(transferId, QUIET)).as("a second pacs.008").isNull();
        answer(Pacs002.answering(payment, TransactionStatus.ACSC, newMessageId(), now()));

        assertThat(nextEvent(transferId).get("status")).isEqualTo("SETTLED");
    }

    @Test
    void anEventThatIsNoTransferGoesToTheDeadLetterTopicAndTheNextEventStillGoesOut() throws Exception {
        String notATransfer = "{\"transferId\": \"not-a-uuid\"}";
        // The payer's key, so it shares a partition with the transfer after it, which must not wait behind it.
        bankCore.publish(TransferRequested.TOPIC, OUR_CUSTOMER, notATransfer);
        UUID transferId = UUID.randomUUID();
        requestTransfer(transferId, PARTNER_CUSTOMER);

        ConsumerRecord<String, String> deadLetter =
                bankCore.records(DEAD_LETTER_TOPIC, TestKafka.keyed(OUR_CUSTOMER), 1).getFirst();
        assertThat(deadLetter.value()).isEqualTo(notATransfer);
        assertThat(TestKafka.headerOf(deadLetter, "dead-letter-reason")).contains("TransferRequested");
        assertThat(TestKafka.headerOf(deadLetter, "dead-letter-origin")).startsWith(TransferRequested.TOPIC + "-");

        Pacs008 payment = takePacs008(transferId);
        answer(Pacs002.answering(payment, TransactionStatus.ACSC, newMessageId(), now()));
        assertThat(nextEvent(transferId).get("status")).isEqualTo("SETTLED");
    }

    @Test
    void aTransferOurBankCannotSendGoesBackAsRejectedOnceAndWithoutAPacs008() throws Exception {
        UUID transferId = UUID.randomUUID();
        requestTransfer(transferId, UNKNOWN_BANKS_CUSTOMER);
        requestTransfer(transferId, UNKNOWN_BANKS_CUSTOMER);

        Map<?, ?> status = nextEvent(transferId);
        assertThat(status.get("status")).isEqualTo("REJECTED");
        assertThat(String.valueOf(status.get("reportId"))).hasSize(32);
        assertThat(pacs008About(transferId, QUIET)).as("a pacs.008").isNull();
        // The repeated event finds the refusal kept, and is not decided on again.
        assertThat(bankCore.recordsWithin(TransferStatusChanged.TOPIC, TestKafka.keyed(transferId.toString()), 2,
                QUIET)).hasSize(1);
    }

    @Test
    void aLateAnswerIsAskedForAndPendingIsReportedFirst() throws Exception {
        UUID transferId = UUID.randomUUID();
        requestTransfer(transferId, PARTNER_CUSTOMER);
        Pacs008 payment = takePacs008(transferId);
        answer(Pacs002.answering(payment, TransactionStatus.PDNG, newMessageId(), now()));

        Pacs028 request = takePacs028(transferId);
        assertThat(request.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(request.instructingAgentBic()).isEqualTo("BNKCUAUKXXX");
        assertThat(request.instructedAgentBic()).isEqualTo("PRTBUAUKXXX");
        answer(Pacs002.answering(payment, TransactionStatus.ACSC, newMessageId(), now()));

        assertThat(events(transferId, 2)).extracting(event -> String.valueOf(event.get("status")))
                .containsExactly("PENDING", "SETTLED");
    }

    @Test
    void aPaymentTheOtherBankNeverReceivedGoesOutAgainAndIsNotRejected() throws Exception {
        UUID transferId = UUID.randomUUID();
        requestTransfer(transferId, PARTNER_CUSTOMER);
        // Taken off the queue and dropped: the partner bank never sees it.
        Pacs008 lost = takePacs008(transferId);

        Pacs028 request = takePacs028(transferId);
        answer(Pacs002.notReceived(request, newMessageId(), now()));

        Pacs008 again = takePacs008(transferId);
        assertThat(again).isEqualTo(lost);
        answer(Pacs002.answering(again, TransactionStatus.ACSC, newMessageId(), now()));

        // The first status bank-core hears of is the settlement: "not received" never reached it as a refusal.
        assertThat(nextEvent(transferId).get("status")).isEqualTo("SETTLED");
    }

    @Test
    void anUnreadableAnswerGoesToTheDeadLetterQueueAsItCame() {
        rabbit.convertAndSend(EXCHANGE, BANK_PACS002, "<Document/>");

        Message dead = partnerBank.deadLetter(BANK_PACS002_DLQ, TIMEOUT);
        assertThat(TestRabbit.bodyOf(dead)).isEqualTo("<Document/>");
        assertThat(TestRabbit.reasonOf(dead)).contains(Pacs002.NAMESPACE);
    }

    @Test
    void anAnswerAboutNoPaymentOfOursGoesToTheDeadLetterQueue() {
        String unknownPayment = newMessageId();
        answer(new Pacs002(newMessageId(), now(), unknownPayment, "NOTPROVIDED", unknownPayment,
                UUID.randomUUID().toString(), TransactionStatus.ACSC, null));

        Message dead = partnerBank.deadLetter(BANK_PACS002_DLQ, TIMEOUT);
        assertThat(TestRabbit.reasonOf(dead)).contains(unknownPayment).contains("not a payment of ours");
    }

    @Test
    void anAnswerThatContradictsTheFinalOneGoesToTheDeadLetterQueue() throws Exception {
        UUID transferId = UUID.randomUUID();
        requestTransfer(transferId, PARTNER_CUSTOMER);
        Pacs008 payment = takePacs008(transferId);
        answer(Pacs002.answering(payment, TransactionStatus.ACSC, newMessageId(), now()));
        assertThat(nextEvent(transferId).get("status")).isEqualTo("SETTLED");

        answer(Pacs002.rejecting(payment, StatusReason.CLOSED_ACCOUNT, newMessageId(), now()));

        Message dead = partnerBank.deadLetter(BANK_PACS002_DLQ, TIMEOUT);
        assertThat(TestRabbit.reasonOf(dead)).contains(transferId.toString()).contains("SETTLED already");
    }

    /** Publishes a transfer as bank-core does: its event as JSON text, keyed by the payer's IBAN. */
    private void requestTransfer(UUID transferId, String payeeIban) throws Exception {
        bankCore.publish(TransferRequested.TOPIC, OUR_CUSTOMER, """
                {"transferId": "%s",
                 "fromIban": "%s", "fromName": "Artem Tkachuk",
                 "toIban": "%s", "toName": "Olena Kovalenko",
                 "amount": 150.00, "currency": "UAH", "description": "Rent for November",
                 "requestedAt": "2026-11-17T08:30:00Z"}
                """.formatted(transferId, OUR_CUSTOMER, payeeIban));
    }

    /** The first status event about the transfer. */
    private Map<?, ?> nextEvent(UUID transferId) throws Exception {
        return events(transferId, 1).getFirst();
    }

    /** The first statuses about the transfer, as many as asked for, in the order bank-core reads them. */
    private List<Map<?, ?>> events(UUID transferId, int count) throws Exception {
        List<Map<?, ?>> events = new ArrayList<>();
        for (ConsumerRecord<String, String> record :
                bankCore.records(TransferStatusChanged.TOPIC, TestKafka.keyed(transferId.toString()), count)) {
            events.add(new JsonMapper().readValue(record.value(), Map.class));
        }
        return events;
    }

    private Pacs008 takePacs008(UUID transferId) {
        Pacs008 payment = pacs008About(transferId, TIMEOUT);
        assertThat(payment).as("a pacs.008 for transfer " + transferId + " on " + PARTNER_PACS008).isNotNull();
        return payment;
    }

    /** The next pacs.008 about the transfer within the wait, or null; others on the queue are skipped. */
    private Pacs008 pacs008About(UUID transferId, Duration wait) {
        return partnerBank.next(PARTNER_PACS008, wait, Pacs008::parse,
                payment -> payment.uetr().equals(transferId.toString()));
    }

    private Pacs028 takePacs028(UUID transferId) {
        Pacs028 request = partnerBank.next(PARTNER_PACS028, TIMEOUT, Pacs028::parse,
                candidate -> candidate.originalUetr().equals(transferId.toString()));
        assertThat(request).as("a pacs.028 about transfer " + transferId + " on " + PARTNER_PACS028).isNotNull();
        return request;
    }

    /** Answers as the partner bank does. */
    private void answer(Pacs002 report) {
        rabbit.convertAndSend(EXCHANGE, BANK_PACS002, report.toXml());
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
