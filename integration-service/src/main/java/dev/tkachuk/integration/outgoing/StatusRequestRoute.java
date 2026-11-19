package dev.tkachuk.integration.outgoing;

import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS028;

import dev.tkachuk.integration.messaging.Routes;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Asks about the payments whose answer is late. Every pollInterval, each payment due a status request gets a
 * pacs.028, and StatusRequestPolicy says when to ask next; the answers come back through AnswerRoute like any
 * other pacs.002. A payment with no final answer is never refused for that: from alarm-after requests on, each
 * request is logged as an error, for a person to take the payment up with the other bank.
 *
 * <p>Nothing here needs retrying: a payment whose request fails (RabbitMQ or PostgreSQL down) keeps its claim
 * until the lease runs out, a minute later, and is claimed again then.
 */
@Component
class StatusRequestRoute extends RouteBuilder {

    /** How long a claimed payment is held back from the next claim while its request goes out. */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(1);

    /** The most payments asked about per poll; the rest wait for the next poll. */
    static final int BATCH_SIZE = 100;

    private static final String PAYMENT = "payment";

    private final OutgoingPayments payments;
    private final StatusRequestPolicy policy;
    private final Clock clock;

    StatusRequestRoute(OutgoingPayments payments, StatusRequestPolicy policy, Clock clock) {
        this.payments = payments;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .handled(true)
                .maximumRedeliveries(0)
                .process(exchange -> log.warn("A status request failed; it is tried again after its lease: {}",
                        Routes.failureOf(exchange)));

        from("timer:status-requests?period=" + policy.pollInterval().toMillis())
                .routeId("unanswered-to-pacs028")
                .process(this::claimDuePayments)
                .split(body())
                    .process(this::toPacs028)
                    .to(Routes.toQueue(PARTNER_PACS028))
                    .process(this::recordRequest)
                .end();
    }

    private void claimDuePayments(Exchange exchange) {
        Instant now = clock.instant();
        exchange.getMessage().setBody(payments.claimDue(now, now.plus(CLAIM_LEASE), BATCH_SIZE));
    }

    /** About the pacs.008 as stored: a resend went out with the same MsgId, so it is the same payment. */
    private void toPacs028(Exchange exchange) {
        OutgoingPayment payment = exchange.getMessage().getBody(OutgoingPayment.class);
        Pacs028 request = Pacs028.about(Pacs008.parse(payment.pacs008()), Routes.newMessageId(),
                clock.instant().truncatedTo(ChronoUnit.SECONDS));
        exchange.setProperty(PAYMENT, payment);
        Routes.asInterbankMessage(exchange, request.messageId(), request.toXml());
    }

    private void recordRequest(Exchange exchange) {
        OutgoingPayment payment = exchange.getProperty(PAYMENT, OutgoingPayment.class);
        int requests = payment.statusRequests() + 1;
        payments.recordStatusRequest(payment.transferId(), payment.timesSent(),
                policy.nextCheck(clock.instant(), requests));
        if (policy.needsAttention(requests)) {
            log.error("Transfer {} has no final answer after {} status requests about pacs.008 {}, sent at {}; "
                    + "take it up with the partner bank", payment.transferId(), requests, payment.messageId(),
                    payment.sentAt());
        } else {
            log.info("Transfer {} has no final answer yet: status request {} about pacs.008 {}", payment.transferId(),
                    requests, payment.messageId());
        }
    }
}
