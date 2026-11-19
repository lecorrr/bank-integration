package dev.tkachuk.integration.outgoing;

import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;

import dev.tkachuk.integration.events.EventJson;
import dev.tkachuk.integration.events.InvalidEventException;
import dev.tkachuk.integration.messaging.Routes;
import dev.tkachuk.integration.screening.SanctionsScreening;
import dev.tkachuk.integration.screening.Screening;
import dev.tkachuk.integration.screening.ScreeningRefusedException;
import dev.tkachuk.interbank.iso20022.InvalidMessageException;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Flow A: bank-core's transfer event becomes a stored payment, is screened, and goes out as a pacs.008. The Kafka
 * offset is committed last, so after a crash the event comes again and the payment continues from its stored state.
 */
@Component
class TransferRoute extends RouteBuilder {

    static final String DEAD_LETTER_TOPIC = TransferRequested.TOPIC + ".DLT";

    private static final String PAYMENT = "payment";
    private static final String REFUSAL = "refusal";

    private final EventJson json;
    private final Pacs008Mapper mapper;
    private final OutgoingPayments payments;
    private final StatusRequestPolicy policy;
    private final SanctionsScreening screening;
    private final Clock clock;

    TransferRoute(EventJson json, Pacs008Mapper mapper, OutgoingPayments payments, StatusRequestPolicy policy,
            SanctionsScreening screening, Clock clock) {
        this.json = json;
        this.mapper = mapper;
        this.payments = payments;
        this.policy = policy;
        this.screening = screening;
        this.clock = clock;
    }

    @Override
    public void configure() {
        errorHandler(Routes.retryInRounds(defaultErrorHandler()));

        onException(InvalidEventException.class, ScreeningRefusedException.class)
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
                .process(this::screen)
                .choice()
                    .when(TransferRoute::clearToSend)
                        .process(this::toPacs008Message)
                        .to(Routes.toQueue(PARTNER_PACS008))
                        .process(this::recordSent)
                    .when(TransferRoute::onASanctionsList)
                        .process(this::toSanctionsRefusal)
                        .to(Routes.toKafka(TransferStatusChanged.TOPIC))
                        .process(this::recordSanctionsRefusal)
                .end()
                .process(Routes::commitOffset);
    }

    private void store(Exchange exchange) {
        TransferRequested transfer = json.read(exchange.getMessage().getBody(String.class), TransferRequested.class);
        OutgoingPayment stored = payments.find(transfer.transferId()).orElseGet(() -> storeNew(transfer));
        if (stored.state() != PaymentState.NEW) {
            log.info("Transfer {} received again, already {}", transfer.transferId(), stored.state());
        }
        exchange.setProperty(PAYMENT, stored);
    }

    private OutgoingPayment storeNew(TransferRequested transfer) {
        Pacs008 payment = mapper.toPacs008(transfer);
        return payments.insertIfAbsent(transfer.transferId(), payment.messageId(), xmlOf(transfer, payment),
                clock.instant());
    }

    private static String xmlOf(TransferRequested transfer, Pacs008 payment) {
        try {
            return payment.toXml();
        } catch (InvalidMessageException e) {
            throw new TransferRefusedException(transfer.transferId(), "invalid pacs.008: " + e.getMessage());
        }
    }

    private void screen(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        if (payment.state() != PaymentState.NEW || payment.screening() != null) {
            return;
        }
        Screening answer = screening.screenOutgoing(Pacs008.parse(payment.pacs008()));
        OutgoingPayment screened = payments.recordScreening(payment.transferId(), answer);
        if (screened.screening().cleared()) {
            log.info("Transfer {} passed sanctions screening {}", screened.transferId(),
                    screened.screening().screeningId());
        }
        exchange.setProperty(PAYMENT, screened);
    }

    private static boolean clearToSend(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        return payment.state() == PaymentState.NEW && payment.screening().cleared();
    }

    private static boolean onASanctionsList(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        return payment.state() == PaymentState.NEW && !payment.screening().cleared();
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
        log.error("Moving event {} to {}: {}", origin, DEAD_LETTER_TOPIC, Routes.failureOf(exchange));
    }

    private void toRefusal(Exchange exchange) {
        TransferRefusedException refusal =
                exchange.getProperty(Exchange.EXCEPTION_CAUGHT, TransferRefusedException.class);
        TransferStatusChanged rejected = rejectedByOurBank(refusal.transferId());
        log.warn("{}, reported as REJECTED (report {})", refusal.getMessage(), rejected.reportId());
        toBankCore(exchange, rejected);
    }

    private void recordRefusal(Exchange exchange) {
        TransferStatusChanged rejected = exchange.getProperty(REFUSAL, TransferStatusChanged.class);
        payments.recordRefusal(rejected.transferId(), Pacs008Mapper.messageIdOf(rejected.transferId()),
                rejected.reportId(), rejected.reportedAt());
    }

    private void toSanctionsRefusal(Exchange exchange) {
        OutgoingPayment payment = paymentOf(exchange);
        TransferStatusChanged rejected = rejectedByOurBank(payment.transferId());
        log.warn("Transfer {} refused: sanctions hit (screening {}), reported as REJECTED (report {})",
                payment.transferId(), payment.screening().screeningId(), rejected.reportId());
        toBankCore(exchange, rejected);
    }

    private void recordSanctionsRefusal(Exchange exchange) {
        TransferStatusChanged rejected = exchange.getProperty(REFUSAL, TransferStatusChanged.class);
        payments.recordSanctionsRefusal(rejected.transferId(), rejected.reportId(), rejected.reportedAt());
    }

    private TransferStatusChanged rejectedByOurBank(UUID transferId) {
        return new TransferStatusChanged(transferId, TransferStatus.REJECTED, Routes.newMessageId(),
                clock.instant().truncatedTo(ChronoUnit.SECONDS));
    }

    private void toBankCore(Exchange exchange, TransferStatusChanged rejected) {
        exchange.setProperty(REFUSAL, rejected);
        Routes.asKafkaEvent(exchange, rejected.transferId().toString(), json.write(rejected));
    }

    private static OutgoingPayment paymentOf(Exchange exchange) {
        return exchange.getProperty(PAYMENT, OutgoingPayment.class);
    }
}
