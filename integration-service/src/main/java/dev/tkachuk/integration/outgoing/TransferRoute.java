package dev.tkachuk.integration.outgoing;

import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;

import dev.tkachuk.integration.events.EventJson;
import dev.tkachuk.integration.events.InvalidEventException;
import dev.tkachuk.integration.messaging.Routes;
import dev.tkachuk.interbank.iso20022.InvalidMessageException;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Flow A, first leg: bank-core's transfer event comes from Kafka, the payment is stored, and its pacs.008 goes
 * out over RabbitMQ. The answers come back through AnswerRoute.
 *
 * <p>At least once, and a repeat is harmless. The route commits the event's Kafka offset itself, as its last
 * step, once the payment is stored and RabbitMQ has confirmed the pacs.008 (Routes.fromKafka says why not
 * auto-commit). A crash before that brings the event again; the stored payment is found, and sent only if it
 * never went out. Should the other bank get the same pacs.008 twice all the same, it drops the copy by its MsgId.
 * A transfer that has a row is never decided again, so a repeated event cannot turn a payment sent into a
 * refusal, or the other way round. A failed step is retried in rounds (Routes.retryInRounds) until it works.
 *
 * <p>Two failures are final and not retried: an event that is not a valid TransferRequested goes to the
 * dead-letter topic as it came, and a transfer our bank cannot send goes back to bank-core as REJECTED, and is
 * then kept as rejected (bank-core first, as in AnswerRoute, so a crash in between repeats the event).
 */
@Component
class TransferRoute extends RouteBuilder {

    /** Where an event that is not a valid TransferRequested goes, as it came; Spring Kafka's naming. */
    static final String DEAD_LETTER_TOPIC = TransferRequested.TOPIC + ".DLT";

    /** The payment as stored. */
    private static final String PAYMENT = "payment";
    /** Our bank's refusal, as bank-core hears of it. */
    private static final String REFUSAL = "refusal";

    private final EventJson json;
    private final Pacs008Mapper mapper;
    private final OutgoingPayments payments;
    private final StatusRequestPolicy policy;
    private final Clock clock;

    TransferRoute(EventJson json, Pacs008Mapper mapper, OutgoingPayments payments, StatusRequestPolicy policy,
            Clock clock) {
        this.json = json;
        this.mapper = mapper;
        this.payments = payments;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public void configure() {
        errorHandler(Routes.retryInRounds(defaultErrorHandler()));

        onException(InvalidEventException.class)
                .handled(true)
                .maximumRedeliveries(0)
                .useOriginalMessage()
                .process(this::toDeadLetter)
                .to(Routes.toKafka(DEAD_LETTER_TOPIC))
                .process(Routes::commitOffset);

        onException(TransferRefusedException.class)
                .handled(true)
                .maximumRedeliveries(0)
                .process(this::toRefusal)
                .to(Routes.toKafka(TransferStatusChanged.TOPIC))
                .process(this::recordRefusal)
                .process(Routes::commitOffset);

        from(Routes.fromKafka(TransferRequested.TOPIC))
                .routeId("transfer-to-pacs008")
                .process(Routes::keepOffsetCommit)
                .process(this::store)
                .filter(TransferRoute::notSentYet)
                    .process(this::toPacs008Message)
                    .to(Routes.toQueue(PARTNER_PACS008))
                    .process(this::recordSent)
                .end()
                .process(Routes::commitOffset);
    }

    /** Reads the event and finds its payment, or makes the pacs.008 and stores it as NEW. */
    private void store(Exchange exchange) {
        TransferRequested transfer = json.read(exchange.getMessage().getBody(String.class), TransferRequested.class);
        OutgoingPayment stored = payments.find(transfer.transferId()).orElseGet(() -> storeNew(transfer));
        if (stored.state() != PaymentState.NEW) {
            log.info("Transfer {} came again; it was decided on already and is {}", transfer.transferId(),
                    stored.state());
        }
        exchange.setProperty(PAYMENT, stored);
    }

    /** @throws TransferRefusedException when our bank cannot send the transfer */
    private OutgoingPayment storeNew(TransferRequested transfer) {
        Pacs008 payment = mapper.toPacs008(transfer);
        return payments.insertIfAbsent(transfer.transferId(), payment.messageId(), xmlOf(transfer, payment),
                clock.instant());
    }

    /** A transfer that cannot make a valid pacs.008 cannot be sent at all: a refusal, not a failure to retry. */
    private static String xmlOf(TransferRequested transfer, Pacs008 payment) {
        try {
            return payment.toXml();
        } catch (InvalidMessageException e) {
            throw new TransferRefusedException(transfer.transferId(),
                    "its pacs.008 would be invalid: " + e.getMessage());
        }
    }

    /** NEW: stored, and RabbitMQ has not confirmed its pacs.008, either just now or before a crash. */
    private static boolean notSentYet(Exchange exchange) {
        return paymentOf(exchange).state() == PaymentState.NEW;
    }

    private void toPacs008Message(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        log.info("Sending pacs.008 {} for transfer {} to the partner bank", payment.messageId(), payment.transferId());
        Routes.asInterbankMessage(exchange, payment.messageId(), payment.pacs008());
    }

    private void recordSent(Exchange exchange) {
        Instant now = clock.instant();
        payments.recordSent(paymentOf(exchange).transferId(), now, policy.firstCheck(now));
    }

    private void toDeadLetter(Exchange exchange) {
        String origin = Routes.asKafkaDeadLetter(exchange);
        log.error("The event at {} is not a valid TransferRequested; it goes to {} for a person to look at: {}",
                origin, DEAD_LETTER_TOPIC, Routes.failureOf(exchange));
    }

    /** REJECTED for bank-core, under an id of its own: no other bank's report decided it. */
    private void toRefusal(Exchange exchange) {
        TransferRefusedException refusal =
                exchange.getProperty(Exchange.EXCEPTION_CAUGHT, TransferRefusedException.class);
        TransferStatusChanged rejected = new TransferStatusChanged(refusal.transferId(), TransferStatus.REJECTED,
                Routes.newMessageId(), clock.instant().truncatedTo(ChronoUnit.SECONDS));
        log.warn("{}; reported to bank-core as REJECTED (report {})", refusal.getMessage(), rejected.reportId());
        exchange.setProperty(REFUSAL, rejected);
        // Keyed by transfer, like every status, so the statuses of one transfer stay in order.
        Routes.asKafkaEvent(exchange, rejected.transferId().toString(), json.write(rejected));
    }

    private void recordRefusal(Exchange exchange) {
        TransferStatusChanged rejected = exchange.getProperty(REFUSAL, TransferStatusChanged.class);
        payments.recordRefusal(rejected.transferId(), Pacs008Mapper.messageIdOf(rejected.transferId()),
                rejected.reportId(), rejected.reportedAt());
    }

    private static OutgoingPayment paymentOf(Exchange exchange) {
        return exchange.getProperty(PAYMENT, OutgoingPayment.class);
    }
}
