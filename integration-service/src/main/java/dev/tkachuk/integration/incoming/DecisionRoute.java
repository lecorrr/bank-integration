package dev.tkachuk.integration.incoming;

import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS002;

import dev.tkachuk.integration.events.EventJson;
import dev.tkachuk.integration.events.InvalidEventException;
import dev.tkachuk.integration.messaging.Routes;
import dev.tkachuk.interbank.iso20022.Pacs002;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Flow B, second leg: bank-core's decision on a payment from the partner bank comes from Kafka, becomes our answer,
 * and goes to the partner bank as a pacs.002: ACSC for a payment credited, RJCT with an ISO reason code for one
 * refused (IncomingTransferDecided says which).
 *
 * <p>At least once, and a repeat is harmless. The answer is stored, then sent, then the event's offset committed
 * (Routes.fromKafka). A crash in between brings the decision again, and the stored answer goes out again: the same
 * pacs.002, which the partner bank can drop as a copy by its MsgId. bank-core sends its decision again too, when
 * a payment comes to it twice; that is answered the same way. A step that fails is retried in rounds
 * (Routes.retryInRounds) until it works.
 *
 * <p>A decision that cannot be acted on goes to the dead-letter topic as it came, for a person to look at: one that
 * is not a valid IncomingTransferDecided, one about a payment we never received, and one that contradicts the
 * answer we gave already.
 */
@Component
class DecisionRoute extends RouteBuilder {

    /** Where a decision that cannot be acted on goes, as it came; Spring Kafka's naming. */
    static final String DEAD_LETTER_TOPIC = IncomingTransferDecided.TOPIC + ".DLT";

    private final IncomingPayments payments;
    private final EventJson json;
    private final Clock clock;

    DecisionRoute(IncomingPayments payments, EventJson json, Clock clock) {
        this.payments = payments;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void configure() {
        errorHandler(Routes.retryInRounds(defaultErrorHandler()));

        onException(InvalidEventException.class, UnusableMessageException.class)
                .handled(true)
                .maximumRedeliveries(0)
                .useOriginalMessage()
                .process(this::toDeadLetter)
                .to(Routes.toKafka(DEAD_LETTER_TOPIC))
                .process(Routes::commitOffset);

        from(Routes.fromKafka(IncomingTransferDecided.TOPIC))
                .routeId("decision-to-pacs002")
                .process(Routes::keepOffsetCommit)
                .process(this::answer)
                .to(Routes.toQueue(PARTNER_PACS002))
                .process(Routes::commitOffset);
    }

    /** Records the decision as our answer, unless the payment has one, and makes the message in hand that answer. */
    private void answer(Exchange exchange) {
        IncomingTransferDecided decision =
                json.read(exchange.getMessage().getBody(String.class), IncomingTransferDecided.class);
        IncomingPayment payment = payments.find(decision.uetr()).orElseThrow(() -> new UnusableMessageException(
                "bank-core decided " + decision.decision() + " on payment " + decision.uetr()
                        + ", which this service never received"));
        boolean first = false;
        if (!payment.answered()) {
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            first = payments.recordAnswer(decision.uetr(), decision.status(), decision.reasonCode(),
                    Routes.newMessageId(), now, decision.transferId());
            payment = payments.find(decision.uetr()).orElseThrow();
        }
        if (!payment.answers(decision)) {
            throw new UnusableMessageException("bank-core decided " + decision.decision() + " on payment "
                    + payment.uetr() + " as transfer " + decision.transferId() + ", which we answered "
                    + payment.outcome() + " already (pacs.002 " + payment.reportId() + ")");
        }
        if (first) {
            log.info("Payment {} (pacs.008 {}): bank-core {} it as transfer {}; answering pacs.002 {} {}",
                    payment.uetr(), payment.messageId(), decision.decision(), decision.transferId(),
                    payment.reportId(), payment.outcome());
        } else {
            log.info("Payment {}: bank-core's decision came again; sending pacs.002 {} {} again", payment.uetr(),
                    payment.reportId(), payment.outcome());
        }
        Pacs002 answer = payment.answer();
        Routes.asInterbankMessage(exchange, answer.messageId(), answer.toXml());
    }

    private void toDeadLetter(Exchange exchange) {
        String origin = Routes.asKafkaDeadLetter(exchange);
        log.error("The event at {} cannot be acted on; it goes to {} for a person to look at: {}", origin,
                DEAD_LETTER_TOPIC, Routes.failureOf(exchange));
    }
}
