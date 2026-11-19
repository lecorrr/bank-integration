package dev.tkachuk.integration.incoming;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS028;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS028_DLQ;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS002;

import dev.tkachuk.integration.InterbankProperties;
import dev.tkachuk.integration.messaging.Routes;
import dev.tkachuk.interbank.iso20022.InvalidMessageException;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/** Flow B: answers the partner bank's pacs.028 from what is stored. */
@Component
class IncomingStatusRequestRoute extends RouteBuilder {

    private final IncomingPayments payments;
    private final InterbankProperties bank;
    private final Clock clock;

    IncomingStatusRequestRoute(IncomingPayments payments, InterbankProperties bank, Clock clock) {
        this.payments = payments;
        this.bank = bank;
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
                .to(Routes.toQueue(BANK_PACS028_DLQ));

        from(Routes.fromQueue(BANK_PACS028))
                .routeId("pacs028-to-pacs002")
                .convertBodyTo(String.class)
                .process(this::answer)
                .to(Routes.toQueue(PARTNER_PACS002));
    }

    private void answer(Exchange exchange) {
        Pacs028 request = Pacs028.parse(exchange.getMessage().getBody(String.class));
        requireFromParticipantToUs(request);
        Pacs002 answer = answerTo(request);
        log.info("pacs.028 {} for pacs.008 {} (payment {}): answering pacs.002 {} {}{}", request.messageId(),
                request.originalMessageId(), request.originalUetr(), answer.messageId(), answer.status(),
                answer.reasonCode() == null ? "" : " " + answer.reasonCode());
        Routes.asInterbankMessage(exchange, answer.messageId(), answer.toXml());
    }

    private Pacs002 answerTo(Pacs028 request) {
        Optional<IncomingPayment> asked =
                payments.findByMessageId(request.instructingAgentBic(), request.originalMessageId());
        if (asked.isPresent()) {
            IncomingPayment payment = asked.get();
            if (!payment.uetr().toString().equals(request.originalUetr())) {
                throw new UnusableMessageException("pacs.028 " + request.messageId()
                        + " has the wrong UETR for pacs.008 " + payment.messageId());
            }
            return payment.answered()
                    ? payment.answer().repeat(Routes.newMessageId(), now())
                    : Pacs002.answering(Pacs008.parse(payment.pacs008()), TransactionStatus.PDNG,
                            Routes.newMessageId(), now());
        }
        boolean copyOfAPayment = payments.find(UUID.fromString(request.originalUetr())).isPresent();
        return Pacs002.rejecting(request,
                copyOfAPayment ? StatusReason.DUPLICATION : StatusReason.PAYMENT_NOT_RECEIVED,
                Routes.newMessageId(), now());
    }

    private void requireFromParticipantToUs(Pacs028 request) {
        if (!bank.bic().equals(request.instructedAgentBic())) {
            throw new UnusableMessageException("pacs.028 " + request.messageId() + " is for bank "
                    + request.instructedAgentBic() + ", not ours");
        }
        if (!bank.participants().containsValue(request.instructingAgentBic())) {
            throw new UnusableMessageException("pacs.028 " + request.messageId() + " is from bank "
                    + request.instructingAgentBic() + ", which is not in the directory of participants");
        }
    }

    private void toDeadLetter(Exchange exchange) {
        log.error("Moving pacs.028 to {}: {}", BANK_PACS028_DLQ, Routes.failureOf(exchange));
        Routes.asQueueDeadLetter(exchange);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
