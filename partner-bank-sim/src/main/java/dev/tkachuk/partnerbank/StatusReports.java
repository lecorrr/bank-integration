package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The answers this bank gives. It remembers the last 10 000 payments, so a repeated pacs.008 or a pacs.028 gets
 * the same answer as the first time.
 */
@Component
class StatusReports {

    static final int REMEMBERED_PAYMENTS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(StatusReports.class);

    private final Clock clock;
    private final PartnerBankProperties properties;
    private final Map<String, Received> received;

    @Autowired
    StatusReports(Clock clock, PartnerBankProperties properties) {
        this(clock, properties, REMEMBERED_PAYMENTS);
    }

    StatusReports(Clock clock, PartnerBankProperties properties, int rememberedPayments) {
        this.clock = clock;
        this.properties = properties;
        this.received = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Received> eldest) {
                return size() > rememberedPayments;
            }
        };
    }

    synchronized Optional<Pacs002> answerPayment(String pacs008Xml) {
        Pacs008 payment = Pacs008.parse(pacs008Xml);
        Received earlier = received.get(payment.messageId());
        if (earlier != null) {
            log.info("Duplicate pacs.008 {}, repeating the answer", payment.messageId());
            return Optional.ofNullable(earlier.answer()).map(answer -> answer.repeat(newMessageId(), now()));
        }
        Pacs002 answer = decide(payment);
        received.put(payment.messageId(), new Received(payment, answer));
        return Optional.ofNullable(answer);
    }

    synchronized Pacs002 answerStatusRequest(String pacs028Xml) {
        Pacs028 request = Pacs028.parse(pacs028Xml);
        Received earlier = received.get(request.originalMessageId());
        Pacs002 answer;
        if (earlier == null) {
            answer = Pacs002.notReceived(request, newMessageId(), now());
        } else if (earlier.answer() == null) {
            answer = Pacs002.answering(earlier.payment(), TransactionStatus.PDNG, newMessageId(), now());
        } else {
            answer = earlier.answer().repeat(newMessageId(), now());
        }
        log.info("pacs.028 {} for pacs.008 {}: answering pacs.002 {} {}{}", request.messageId(),
                request.originalMessageId(), answer.messageId(), answer.status(),
                answer.reasonCode() == null ? "" : " " + answer.reasonCode());
        return answer;
    }

    private Pacs002 decide(Pacs008 payment) {
        if (properties.isSilent(payment.creditorIban())) {
            log.info("pacs.008 {}: silent account, not answering", payment.messageId());
            return null;
        }
        if (properties.isClosed(payment.creditorIban())) {
            Pacs002 refusal = Pacs002.rejecting(payment, StatusReason.CLOSED_ACCOUNT, newMessageId(), now());
            log.info("pacs.008 {}: account closed, answering pacs.002 {} {} {}", payment.messageId(),
                    refusal.messageId(), refusal.status(), refusal.reasonCode());
            return refusal;
        }
        Pacs002 report = Pacs002.answering(payment, TransactionStatus.ACSC, newMessageId(), now());
        log.info("pacs.008 {}: {} {} credited, answering pacs.002 {} {}", payment.messageId(),
                payment.amount().toPlainString(), payment.currency(), report.messageId(), report.status());
        return report;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Received(Pacs008 payment, Pacs002 answer) {
    }
}
