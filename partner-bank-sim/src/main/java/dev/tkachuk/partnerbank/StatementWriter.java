package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Camt053;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Writes a day of the account as its camt.053, with the faults the demo asked for. */
@Component
class StatementWriter {

    private final CorrespondentAccount account;
    private final StatementProperties properties;
    private final SendingProperties sending;
    private final Clock clock;

    StatementWriter(CorrespondentAccount account, StatementProperties properties, SendingProperties sending,
            Clock clock) {
        this.account = account;
        this.properties = properties;
        this.sending = sending;
        this.clock = clock;
    }

    Camt053 write(CorrespondentAccount.Day day, StatementFaults faults) {
        Set<String> booked = day.bookings().stream()
                .map(CorrespondentAccount.Booking::uetr)
                .collect(Collectors.toSet());
        for (String uetr : faults.payments()) {
            if (!booked.contains(uetr)) {
                throw new IllegalArgumentException("Payment " + uetr + " is not booked on " + day.date());
            }
        }
        List<Camt053.Entry> entries = new ArrayList<>();
        for (CorrespondentAccount.Booking booking : day.bookings()) {
            if (faults.leaveOut().contains(booking.uetr())) {
                continue;
            }
            BigDecimal amount = faults.changeAmounts().getOrDefault(booking.uetr(), booking.amount());
            entries.add(new Camt053.Entry(booking.reference(), booking.direction(), amount, booking.uetr(),
                    booking.endToEndId()));
            if (faults.bookTwice().contains(booking.uetr())) {
                entries.add(new Camt053.Entry(account.nextReference(), booking.direction(), amount, booking.uetr(),
                        booking.endToEndId()));
            }
        }
        for (StatementFaults.Unknown unknown : faults.addUnknown()) {
            entries.add(new Camt053.Entry(account.nextReference(), unknown.direction(), unknown.amount(),
                    UUID.randomUUID().toString(), PaymentSender.NOT_PROVIDED));
        }
        // A statement has to add up on its own, so the closing balance follows the entries it reports.
        BigDecimal closingBalance = day.openingBalance();
        for (Camt053.Entry entry : entries) {
            closingBalance = closingBalance.add(entry.signedAmount());
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        return new Camt053(UUID.randomUUID().toString().replace("-", ""), now,
                "STMT-" + day.date().format(DateTimeFormatter.BASIC_ISO_DATE), properties.accountIban(), sending.bic(),
                CorrespondentAccount.CURRENCY, day.date(), day.openingBalance(), closingBalance, entries);
    }
}
