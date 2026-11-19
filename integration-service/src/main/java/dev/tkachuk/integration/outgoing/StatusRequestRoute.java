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

/** Flow A: sends a pacs.028 for every payment whose status request is due. */
@Component
class StatusRequestRoute extends RouteBuilder {

    static final Duration CLAIM_LEASE = Duration.ofMinutes(1);

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
        // A failed request stays claimed until its lease runs out, then it is picked up again.
        onException(Exception.class)
                .handled(true)
                .maximumRedeliveries(0)
                .process(exchange -> log.warn("Status request failed, will retry after the lease expires: {}",
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
            log.error("Transfer {} has no final answer after {} status requests (pacs.008 {}, sent {}), "
                    + "check with the partner bank", payment.transferId(), requests, payment.messageId(),
                    payment.sentAt());
        } else {
            log.info("Transfer {}: status request {} for pacs.008 {}", payment.transferId(),
                    requests, payment.messageId());
        }
    }
}
