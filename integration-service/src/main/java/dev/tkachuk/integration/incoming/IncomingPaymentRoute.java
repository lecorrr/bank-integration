package dev.tkachuk.integration.incoming;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS008;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS008_DLQ;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS002;

import dev.tkachuk.integration.InterbankProperties;
import dev.tkachuk.integration.events.EventJson;
import dev.tkachuk.integration.incoming.IncomingChecks.Refusal;
import dev.tkachuk.integration.messaging.Routes;
import dev.tkachuk.integration.screening.SanctionsScreening;
import dev.tkachuk.integration.screening.Screening;
import dev.tkachuk.integration.screening.ScreeningRefusedException;
import dev.tkachuk.interbank.iso20022.InvalidMessageException;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.StatusReason;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Flow B: the partner bank's pacs.008 is stored, checked, screened and passed on to bank-core. Every answer is
 * stored before it goes out, so a repeated message gets the same answer.
 */
@Component
class IncomingPaymentRoute extends RouteBuilder {

    private static final String TO_BANK_CORE = "toBankCore";

    private final IncomingPayments payments;
    private final InterbankProperties bank;
    private final SanctionsScreening screening;
    private final EventJson json;
    private final Clock clock;

    IncomingPaymentRoute(IncomingPayments payments, InterbankProperties bank, SanctionsScreening screening,
            EventJson json, Clock clock) {
        this.payments = payments;
        this.bank = bank;
        this.screening = screening;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void configure() {
        errorHandler(Routes.retryUntilItWorks(defaultErrorHandler()));

        onException(InvalidMessageException.class, UnusableMessageException.class, ScreeningRefusedException.class)
                .handled(true)
                .maximumRedeliveries(0)
                .useOriginalMessage()
                .process(this::toDeadLetter)
                .to(Routes.toQueue(BANK_PACS008_DLQ));

        from(Routes.fromQueue(BANK_PACS008))
                .routeId("pacs008-to-bank-core")
                .convertBodyTo(String.class)
                .process(this::receive)
                .choice()
                    .when(exchange -> exchange.getProperty(TO_BANK_CORE, false, Boolean.class))
                        .to(Routes.toKafka(IncomingTransferReceived.TOPIC))
                    .otherwise()
                        .to(Routes.toQueue(PARTNER_PACS002))
                .end();
    }

    private void receive(Exchange exchange) {
        String xml = exchange.getMessage().getBody(String.class);
        Pacs008 payment = Pacs008.parse(xml);
        IncomingPayment stored = payments.find(UUID.fromString(payment.uetr()))
                .orElseGet(() -> storeNew(payment, xml));
        if (!stored.messageId().equals(payment.messageId())) {
            Pacs002 refusal = Pacs002.rejecting(payment, StatusReason.DUPLICATION, Routes.newMessageId(), now());
            log.warn("pacs.008 {} duplicates payment {} (received as pacs.008 {}), rejected AM05 in pacs.002 {}",
                    payment.messageId(), stored.uetr(), stored.messageId(), refusal.messageId());
            toPartnerBank(exchange, refusal);
        } else if (stored.answered()) {
            log.info("pacs.008 {} (payment {}) already answered {}, resending pacs.002 {}", payment.messageId(),
                    stored.uetr(), stored.outcome(), stored.reportId());
            toPartnerBank(exchange, stored.answer());
        } else {
            IncomingPayment screened = stored.screening() == null ? screen(stored) : stored;
            if (screened.answered()) {
                log.warn("pacs.008 {} (payment {}) refused {}: sanctions hit (screening {}), pacs.002 {}",
                        payment.messageId(), screened.uetr(), screened.outcome(),
                        screened.screening().screeningId(), screened.reportId());
                toPartnerBank(exchange, screened.answer());
            } else {
                toBankCore(exchange, screened);
            }
        }
    }

    private IncomingPayment screen(IncomingPayment payment) {
        Screening answer = screening.screenIncoming(Pacs008.parse(payment.pacs008()));
        return answer.cleared()
                ? payments.recordCleared(payment.uetr(), answer)
                : payments.recordSanctionsRefusal(payment.uetr(), answer, Routes.newMessageId(), now());
    }

    private void toBankCore(Exchange exchange, IncomingPayment payment) {
        Pacs008 first = Pacs008.parse(payment.pacs008());
        log.info("pacs.008 {} (payment {}, {} {}) passed sanctions screening {}, forwarding to bank-core",
                first.messageId(), payment.uetr(), first.amount().toPlainString(), first.currency(),
                payment.screening().screeningId());
        IncomingTransferReceived transfer = IncomingTransferReceived.of(first, payment.receivedAt());
        exchange.setProperty(TO_BANK_CORE, true);
        Routes.asKafkaEvent(exchange, transfer.toIban(), json.write(transfer));
    }

    private IncomingPayment storeNew(Pacs008 payment, String xml) {
        Instant now = now();
        Optional<Refusal> refusal = IncomingChecks.refusalOf(payment, bank);
        IncomingPayment fresh = refusal
                .map(it -> IncomingPayment.refused(payment, xml, now, it.reasonCode(), Routes.newMessageId()))
                .orElseGet(() -> IncomingPayment.received(payment, xml, now));
        IncomingPayment stored = payments.insertIfAbsent(fresh).orElseThrow(() -> new UnusableMessageException(
                "pacs.008 " + payment.messageId() + " for payment " + payment.uetr()
                        + " reuses the MsgId of another payment from bank " + payment.debtorAgentBic()));
        if (refusal.isPresent() && fresh.reportId().equals(stored.reportId())) {
            log.warn("pacs.008 {} (payment {}) refused {}: {}", payment.messageId(), payment.uetr(),
                    fresh.outcome(), refusal.get().reason());
        }
        return stored;
    }

    private static void toPartnerBank(Exchange exchange, Pacs002 answer) {
        Routes.asInterbankMessage(exchange, answer.messageId(), answer.toXml());
    }

    private void toDeadLetter(Exchange exchange) {
        log.error("Moving pacs.008 to {}: {}", BANK_PACS008_DLQ, Routes.failureOf(exchange));
        Routes.asQueueDeadLetter(exchange);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
