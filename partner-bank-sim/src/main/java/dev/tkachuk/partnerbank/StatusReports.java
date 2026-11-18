package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The partner bank's decision on an incoming payment: a payment to an account it has closed is refused, every
 * other valid pacs.008 is accepted. A later step adds silence, so bank-integration's handling of an answer that
 * never comes can be tested.
 */
@Component
class StatusReports {

    /** ExternalStatusReason1Code AC04, ClosedAccountNumber: the payee's account is closed at this bank. */
    static final String CLOSED_ACCOUNT = "AC04";

    private static final Logger log = LoggerFactory.getLogger(StatusReports.class);

    private final Clock clock;
    private final PartnerBankProperties properties;

    StatusReports(Clock clock, PartnerBankProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /** @throws dev.tkachuk.interbank.iso20022.InvalidMessageException when the XML is not a valid pacs.008 */
    Pacs002 answer(String pacs008Xml) {
        Pacs008 payment = Pacs008.parse(pacs008Xml);
        String messageId = UUID.randomUUID().toString().replace("-", "");
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (properties.isClosed(payment.creditorIban())) {
            Pacs002 refusal = Pacs002.rejecting(payment, CLOSED_ACCOUNT, messageId, now);
            log.info("pacs.008 {}: the payee's account is closed, answering pacs.002 {} {} {}", payment.messageId(),
                    refusal.messageId(), refusal.status(), refusal.reasonCode());
            return refusal;
        }
        Pacs002 report = Pacs002.answering(payment, TransactionStatus.ACSC, messageId, now);
        log.info("pacs.008 {}: {} {} credited, answering pacs.002 {} {}", payment.messageId(),
                payment.amount().toPlainString(), payment.currency(), report.messageId(), report.status());
        return report;
    }
}
