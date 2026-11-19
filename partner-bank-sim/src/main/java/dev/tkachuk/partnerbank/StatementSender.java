package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Camt053;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends the statement of each day after it ends, or of the open day at once when the demo asks. */
@Component
class StatementSender {

    private static final ZoneId BANK_ZONE = ZoneId.of("Europe/Kyiv");
    private static final Logger log = LoggerFactory.getLogger(StatementSender.class);

    private final CorrespondentAccount account;
    private final StatementWriter writer;
    private final StatementUpload upload;
    private final Clock clock;

    StatementSender(CorrespondentAccount account, StatementWriter writer, StatementUpload upload, Clock clock) {
        this.account = account;
        this.writer = writer;
        this.upload = upload;
        this.clock = clock;
    }

    /** Every day before today without a statement gets one, oldest first. */
    @Scheduled(cron = "0 5 0 * * *", zone = "Europe/Kyiv")
    void sendDueStatements() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), BANK_ZONE);
        while (account.openDay().date().isBefore(today)) {
            send(StatementFaults.NONE);
        }
    }

    /**
     * Sends the open day's statement and closes the day. One at a time, so a day never gets two statements; a
     * failed upload leaves the day open.
     */
    synchronized Sent send(StatementFaults faults) {
        CorrespondentAccount.Day day = account.openDay();
        Camt053 statement = writer.write(day, faults);
        String fileName = "camt053-" + statement.date() + "-" + statement.messageId() + ".xml";
        upload.upload(fileName, statement.toXml());
        account.close(day);
        log.info("Sent statement {} for {} as {}: {} entries, closing balance {}", statement.messageId(),
                statement.date(), fileName, statement.entries().size(), statement.closingBalance().toPlainString());
        return new Sent(statement, fileName);
    }

    record Sent(Camt053 statement, String fileName) {
    }
}
