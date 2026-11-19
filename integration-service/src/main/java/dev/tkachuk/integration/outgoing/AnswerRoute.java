package dev.tkachuk.integration.outgoing;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002_DLQ;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;

import dev.tkachuk.integration.events.EventJson;
import dev.tkachuk.integration.messaging.Routes;
import dev.tkachuk.integration.outgoing.Answers.Action;
import dev.tkachuk.interbank.iso20022.InvalidMessageException;
import dev.tkachuk.interbank.iso20022.Pacs002;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Flow A, second leg: the other bank's pacs.002 answers come from RabbitMQ, to payments and to status requests
 * alike, and Answers decides what each one does. A definite answer goes to bank-core as a status event; "never
 * received" sends the payment again.
 *
 * <p>At least once, and a repeat is harmless. RabbitMQ keeps the message until every step is done: the status
 * event first, then the payment's new state. A crash in between brings the answer again, the event goes out a
 * second time, and bank-core ignores the copy (its ALREADY_APPLIED). A step that fails, with Kafka or PostgreSQL
 * down, is tried until it works (Routes.retryUntilItWorks). On shutdown the message goes back to the queue
 * instead of being dropped, and comes again after the restart.
 *
 * <p>An answer that cannot be acted on is never dropped either: it goes to bank.pacs002.dlq as it came, with
 * the reason in its dead-letter-reason header, for a person to look at.
 */
@Component
class AnswerRoute extends RouteBuilder {

    private static final String PAYMENT = "payment";
    private static final String REPORT = "report";
    private static final String ACTION = "action";

    private final OutgoingPayments payments;
    private final StatusRequestPolicy policy;
    private final EventJson json;
    private final Clock clock;

    AnswerRoute(OutgoingPayments payments, StatusRequestPolicy policy, EventJson json, Clock clock) {
        this.payments = payments;
        this.policy = policy;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void configure() {
        errorHandler(Routes.retryUntilItWorks(defaultErrorHandler()));

        onException(InvalidMessageException.class, UnusableAnswerException.class)
                .handled(true)
                .maximumRedeliveries(0)
                .useOriginalMessage()
                .process(this::toDeadLetter)
                .to(Routes.toQueue(BANK_PACS002_DLQ));

        from(Routes.fromQueue(BANK_PACS002))
                .routeId("pacs002-to-status")
                .convertBodyTo(String.class)
                .process(this::decide)
                .choice()
                    .when(actionIs(Action.SETTLE, Action.REJECT, Action.MARK_PENDING))
                        .process(this::toStatusEvent)
                        .to(Routes.toKafka(TransferStatusChanged.TOPIC))
                        .process(this::recordAnswer)
                    .when(actionIs(Action.RESEND))
                        .process(this::toStoredPacs008)
                        .to(Routes.toQueue(PARTNER_PACS008))
                        .process(this::recordResend)
                    .when(actionIs(Action.ASK_AGAIN))
                        .process(this::askAgain)
                    .otherwise()
                        .process(this::logNothingNew)
                .end();
    }

    /** Reads the answer, finds the payment it is about, and decides; an answer that cannot be acted on throws. */
    private void decide(Exchange exchange) {
        Pacs002 report = Pacs002.parse(exchange.getMessage().getBody(String.class));
        OutgoingPayment payment = payments.findByMessageId(report.originalMessageId())
                .orElseThrow(() -> new UnusableAnswerException("pacs.002 " + report.messageId() + " answers pacs.008 "
                        + report.originalMessageId() + ", which is not a payment of ours"));
        if (!payment.transferId().toString().equals(report.originalUetr())) {
            throw new UnusableAnswerException("pacs.002 " + report.messageId() + " gives our pacs.008 "
                    + payment.messageId() + " another payment's UETR");
        }
        Action action = Answers.decide(payment.state(), report);
        if (action == Action.CONFLICT) {
            throw new UnusableAnswerException("pacs.002 " + report.messageId() + " says " + outcomeOf(report)
                    + " about transfer " + payment.transferId() + ", which is " + payment.state() + " already");
        }
        exchange.setProperty(PAYMENT, payment);
        exchange.setProperty(REPORT, report);
        exchange.setProperty(ACTION, action);
    }

    private void toStatusEvent(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        Pacs002 report = reportOf(exchange);
        TransferStatus status = switch (actionOf(exchange)) {
            case SETTLE -> TransferStatus.SETTLED;
            case REJECT -> TransferStatus.REJECTED;
            case MARK_PENDING -> TransferStatus.PENDING;
            case RESEND, ASK_AGAIN, IGNORE, CONFLICT -> throw new IllegalStateException(
                    actionOf(exchange) + " sends bank-core no status");
        };
        log.info("Transfer {} is {} (pacs.002 {}, {})", payment.transferId(), status, report.messageId(),
                outcomeOf(report));
        // Nothing of the other bank's message reaches bank-core but what the event carries. Keyed by transfer, so
        // the statuses of one transfer stay in order.
        Routes.asKafkaEvent(exchange, payment.transferId().toString(),
                json.write(TransferStatusChanged.reported(payment.transferId(), status, report)));
    }

    /** Recorded only after bank-core has the event: a crash in between sends the event again, and loses nothing. */
    private void recordAnswer(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        switch (actionOf(exchange)) {
            case SETTLE -> payments.recordFinal(payment.transferId(), PaymentState.SETTLED, reportOf(exchange),
                    clock.instant());
            case REJECT -> payments.recordFinal(payment.transferId(), PaymentState.REJECTED, reportOf(exchange),
                    clock.instant());
            case MARK_PENDING -> payments.recordPending(payment.transferId());
            case RESEND, ASK_AGAIN, IGNORE, CONFLICT -> throw new IllegalStateException(
                    actionOf(exchange) + " records no answer");
        }
    }

    /** The pacs.008 exactly as it first went out, so its MsgId lets the other bank drop it if it did arrive. */
    private void toStoredPacs008(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        Pacs002 report = reportOf(exchange);
        log.warn("Transfer {}: the partner bank never received pacs.008 {} (pacs.002 {}, {}); sending it again",
                payment.transferId(), payment.messageId(), report.messageId(), outcomeOf(report));
        Routes.asInterbankMessage(exchange, payment.messageId(), payment.pacs008());
    }

    private void recordResend(Exchange exchange) {
        Instant now = clock.instant();
        payments.recordSent(paymentOf(exchange).transferId(), now, policy.firstCheck(now));
    }

    private void askAgain(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        log.info("Transfer {}: the partner bank refused a copy of pacs.008 {} as a duplicate (pacs.002 {}); asking "
                + "what became of the payment itself", payment.transferId(), payment.messageId(),
                reportOf(exchange).messageId());
        payments.askNow(payment.transferId(), clock.instant());
    }

    private void logNothingNew(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        Pacs002 report = reportOf(exchange);
        log.info("Transfer {} is {}: pacs.002 {} ({}) changes nothing", payment.transferId(), payment.state(),
                report.messageId(), outcomeOf(report));
    }

    private void toDeadLetter(Exchange exchange) {
        log.error("A pacs.002 goes to {} for a person to look at: {}", BANK_PACS002_DLQ, Routes.failureOf(exchange));
        Routes.asQueueDeadLetter(exchange);
    }

    private static Predicate actionIs(Action... actions) {
        Set<Action> wanted = Set.of(actions);
        return exchange -> wanted.contains(actionOf(exchange));
    }

    /** E.g. "RJCT AC04", or "ACSC" when the report gives no reason. */
    private static String outcomeOf(Pacs002 report) {
        return report.reasonCode() == null ? report.status().name() : report.status() + " " + report.reasonCode();
    }

    private static OutgoingPayment paymentOf(Exchange exchange) {
        return exchange.getProperty(PAYMENT, OutgoingPayment.class);
    }

    private static Pacs002 reportOf(Exchange exchange) {
        return exchange.getProperty(REPORT, Pacs002.class);
    }

    private static Action actionOf(Exchange exchange) {
        return exchange.getProperty(ACTION, Action.class);
    }
}
