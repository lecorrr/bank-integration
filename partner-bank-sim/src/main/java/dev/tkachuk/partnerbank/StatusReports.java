package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The partner bank's decision on an incoming payment. In the walking skeleton it accepts everything that is a
 * valid pacs.008; later steps add rejections and silence, so bank-integration's failure handling can be tested.
 */
@Component
class StatusReports {

    private static final Logger log = LoggerFactory.getLogger(StatusReports.class);

    private final Clock clock;

    StatusReports(Clock clock) {
        this.clock = clock;
    }

    /** @throws dev.tkachuk.interbank.iso20022.InvalidMessageException when the XML is not a valid pacs.008 */
    Pacs002 answer(String pacs008Xml) {
        Pacs008 payment = Pacs008.parse(pacs008Xml);
        Pacs002 report = Pacs002.answering(payment, TransactionStatus.ACSC,
                UUID.randomUUID().toString().replace("-", ""), clock.instant().truncatedTo(ChronoUnit.SECONDS));
        log.info("pacs.008 {}: {} {} credited, answering pacs.002 {} {}", payment.messageId(),
                payment.amount().toPlainString(), payment.currency(), report.messageId(), report.status());
        return report;
    }
}
