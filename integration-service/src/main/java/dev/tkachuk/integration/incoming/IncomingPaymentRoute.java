package dev.tkachuk.integration.incoming;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS008;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS008_DLQ;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS002;

import dev.tkachuk.integration.InterbankProperties;
import dev.tkachuk.integration.events.EventJson;
import dev.tkachuk.integration.incoming.IncomingChecks.Refusal;
import dev.tkachuk.integration.messaging.Routes;
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
 * Flow B, first leg: a payment from the partner bank to a customer of bank-core comes over RabbitMQ (pacs.008), is
 * stored, and goes on to bank-core as an IncomingTransferReceived event; bank-core's decision comes back through
 * DecisionRoute. A payment our bank refuses itself (IncomingChecks) never reaches bank-core: the partner bank gets
 * the refusal, a pacs.002 RJCT, at once.
 *
 * <p>At least once, and a repeat is harmless. RabbitMQ keeps the pacs.008 until every step is done. A crash before
 * that brings it again, and it finds its payment stored: one still waiting for bank-core goes to bank-core again,
 * which credits a payment once, by its UETR; one answered gets the same answer again. A refusal is stored before
 * it goes out, for the same reason: whatever comes again is answered from what is stored. A step that fails is
 * tried until it works (Routes.retryUntilItWorks).
 *
 * <p>Another message for a payment we have, with the same UETR under a new MsgId, is refused as a duplicate
 * (RJCT AM05); the payment's own answer stands. A message that cannot be answered at all goes to bank.pacs008.dlq
 * as it came, for a person to look at: one that is not a valid pacs.008, and one that reuses the MsgId of another
 * payment from the same bank, since an answer names the payment by its MsgId.
 */
@Component
class IncomingPaymentRoute extends RouteBuilder {

    /** Whether the message in hand goes to bank-core; otherwise it is an answer for the partner bank. */
    private static final String TO_BANK_CORE = "toBankCore";

    private final IncomingPayments payments;
    private final InterbankProperties bank;
    private final EventJson json;
    private final Clock clock;

    IncomingPaymentRoute(IncomingPayments payments, InterbankProperties bank, EventJson json, Clock clock) {
        this.payments = payments;
        this.bank = bank;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void configure() {
        errorHandler(Routes.retryUntilItWorks(defaultErrorHandler()));

        onException(InvalidMessageException.class, UnusableMessageException.class)
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

    /** Reads the payment, finds it or stores it, and makes the message in hand what goes out next. */
    private void receive(Exchange exchange) {
        String xml = exchange.getMessage().getBody(String.class);
        Pacs008 payment = Pacs008.parse(xml);
        IncomingPayment stored = payments.find(UUID.fromString(payment.uetr()))
                .orElseGet(() -> storeNew(payment, xml));
        if (!stored.messageId().equals(payment.messageId())) {
            Pacs002 refusal = Pacs002.rejecting(payment, StatusReason.DUPLICATION, Routes.newMessageId(), now());
            log.warn("pacs.008 {} is another message for payment {}, which came as pacs.008 {}; refused as a "
                    + "duplicate (pacs.002 {})", payment.messageId(), stored.uetr(), stored.messageId(),
                    refusal.messageId());
            toPartnerBank(exchange, refusal);
        } else if (stored.answered()) {
            log.info("pacs.008 {} (payment {}) is answered {}: sending pacs.002 {}", payment.messageId(),
                    stored.uetr(), stored.outcome(), stored.reportId());
            toPartnerBank(exchange, stored.answer());
        } else {
            // The payment as it first came: a copy cannot change what bank-core is told.
            Pacs008 first = Pacs008.parse(stored.pacs008());
            log.info("pacs.008 {} (payment {}, {} {}) goes to bank-core", first.messageId(), stored.uetr(),
                    first.amount().toPlainString(), first.currency());
            IncomingTransferReceived transfer = IncomingTransferReceived.of(first, stored.receivedAt());
            exchange.setProperty(TO_BANK_CORE, true);
            // Keyed by the payee's account, so the payments to one account keep their order.
            Routes.asKafkaEvent(exchange, transfer.toIban(), json.write(transfer));
        }
    }

    /** Checks the payment and stores it, refused or waiting for bank-core; whichever copy came first decides. */
    private IncomingPayment storeNew(Pacs008 payment, String xml) {
        Instant now = now();
        Optional<Refusal> refusal = IncomingChecks.refusalOf(payment, bank);
        IncomingPayment fresh = refusal
                .map(it -> IncomingPayment.refused(payment, xml, now, it.reasonCode(), Routes.newMessageId()))
                .orElseGet(() -> IncomingPayment.received(payment, xml, now));
        IncomingPayment stored = payments.insertIfAbsent(fresh).orElseThrow(() -> new UnusableMessageException(
                "pacs.008 " + payment.messageId() + " for payment " + payment.uetr()
                        + " reuses the MsgId of another payment from bank " + payment.debtorAgentBic()));
        // Our refusal, unless a copy handled at the same time by another instance of this service stored first.
        if (refusal.isPresent() && fresh.reportId().equals(stored.reportId())) {
            log.warn("pacs.008 {} (payment {}) is refused, {}: {}", payment.messageId(), payment.uetr(),
                    fresh.outcome(), refusal.get().reason());
        }
        return stored;
    }

    private static void toPartnerBank(Exchange exchange, Pacs002 answer) {
        Routes.asInterbankMessage(exchange, answer.messageId(), answer.toXml());
    }

    private void toDeadLetter(Exchange exchange) {
        log.error("A pacs.008 goes to {} for a person to look at: {}", BANK_PACS008_DLQ, Routes.failureOf(exchange));
        Routes.asQueueDeadLetter(exchange);
    }

    /** To the second, as the messages carry it, so an answer stored and made again is the same pacs.002. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
