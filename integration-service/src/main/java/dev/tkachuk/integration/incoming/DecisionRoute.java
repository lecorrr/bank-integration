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

/** Flow B: bank-core's decision becomes our pacs.002 answer, stored before it goes out. */
@Component
class DecisionRoute extends RouteBuilder {

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

    private void answer(Exchange exchange) {
        IncomingTransferDecided decision =
                json.read(exchange.getMessage().getBody(String.class), IncomingTransferDecided.class);
        IncomingPayment payment = payments.find(decision.uetr()).orElseThrow(() -> new UnusableMessageException(
                "Decision " + decision.decision() + " for payment " + decision.uetr()
                        + ", which was never received"));
        boolean first = false;
        if (!payment.answered()) {
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            first = payments.recordAnswer(decision.uetr(), decision.status(), decision.reasonCode(),
                    Routes.newMessageId(), now, decision.transferId());
            payment = payments.find(decision.uetr()).orElseThrow();
        }
        if (!payment.answers(decision)) {
            throw new UnusableMessageException("Decision " + decision.decision() + " for payment " + payment.uetr()
                    + " (transfer " + decision.transferId() + ") conflicts with our answer " + payment.outcome()
                    + " (pacs.002 " + payment.reportId() + ")");
        }
        if (first) {
            log.info("Payment {} (pacs.008 {}) {} by bank-core as transfer {}, sending pacs.002 {} {}",
                    payment.uetr(), payment.messageId(), decision.decision(), decision.transferId(),
                    payment.reportId(), payment.outcome());
        } else {
            log.info("Payment {}: repeated decision, resending pacs.002 {} {}", payment.uetr(),
                    payment.reportId(), payment.outcome());
        }
        Pacs002 answer = payment.answer();
        Routes.asInterbankMessage(exchange, answer.messageId(), answer.toXml());
    }

    private void toDeadLetter(Exchange exchange) {
        String origin = Routes.asKafkaDeadLetter(exchange);
        log.error("Moving event {} to {}: {}", origin, DEAD_LETTER_TOPIC, Routes.failureOf(exchange));
    }
}
