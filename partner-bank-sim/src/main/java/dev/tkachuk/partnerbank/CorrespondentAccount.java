package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.CreditDebit;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * bank-core's bank's account with this bank, in memory. A payment from that bank is debited when this bank accepts
 * it, a payment to that bank is credited when that bank accepts it. Days are closed one by one, each by its
 * statement; a booking made after its day was closed goes to the next day.
 */
@Component
class CorrespondentAccount {

    static final String CURRENCY = "UAH";
    static final int REMEMBERED_PAYMENTS = 10_000;

    private static final ZoneId BANK_ZONE = ZoneId.of("Europe/Kyiv");
    private static final Logger log = LoggerFactory.getLogger(CorrespondentAccount.class);

    private final Clock clock;
    private final Set<String> booked;
    private final List<Booking> bookings = new ArrayList<>();
    private LocalDate openDay;
    private BigDecimal openingBalance;
    private long lastReference;

    @Autowired
    CorrespondentAccount(Clock clock, StatementProperties properties) {
        this(clock, properties.openingBalance(), REMEMBERED_PAYMENTS);
    }

    CorrespondentAccount(Clock clock, BigDecimal openingBalance, int rememberedPayments) {
        this.clock = clock;
        this.openingBalance = openingBalance;
        this.openDay = today();
        // Remembers recent bookings, so a repeated pacs.002 or pacs.008 books nothing new.
        this.booked = Collections.newSetFromMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > rememberedPayments;
            }
        });
    }

    /** A payment from bank-core's bank that this bank accepted: the money leaves its account. */
    synchronized void debit(Pacs008 payment) {
        book(CreditDebit.DBIT, payment);
    }

    /** A payment of this bank's customer that bank-core's bank accepted: the money comes to its account. */
    synchronized void credit(Pacs008 payment) {
        book(CreditDebit.CRDT, payment);
    }

    /** The oldest day without a statement, with its bookings so far. */
    synchronized Day openDay() {
        List<Booking> ofTheDay = bookings.stream().filter(booking -> booking.date().equals(openDay)).toList();
        return new Day(openDay, openingBalance, ofTheDay);
    }

    /** Closes the day once its statement is out. Bookings made while the statement was being sent move on a day. */
    synchronized void close(Day day) {
        if (!day.date().equals(openDay)) {
            throw new IllegalStateException("Day " + day.date() + " is not the open day " + openDay);
        }
        bookings.removeAll(day.bookings());
        openDay = openDay.plusDays(1);
        openingBalance = day.closingBalance();
        bookings.replaceAll(booking -> booking.date().isBefore(openDay) ? booking.on(openDay) : booking);
    }

    /** A new reference for a booking, unique in this account. */
    synchronized String nextReference() {
        lastReference++;
        return "PRTB%010d".formatted(lastReference);
    }

    private void book(CreditDebit direction, Pacs008 payment) {
        if (!CURRENCY.equals(payment.currency())) {
            log.error("Payment {} is in {}, the account is in {}; not booked", payment.uetr(), payment.currency(),
                    CURRENCY);
            return;
        }
        if (!booked.add(direction + " " + payment.uetr())) {
            return;
        }
        LocalDate today = today();
        LocalDate date = today.isAfter(openDay) ? today : openDay;
        Booking booking = new Booking(date, nextReference(), direction, payment.amount(), payment.uetr(),
                payment.endToEndId());
        bookings.add(booking);
        log.info("Booked {} {} {} for payment {} on {}", direction, payment.amount().toPlainString(), CURRENCY,
                payment.uetr(), date);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BANK_ZONE);
    }

    /** One payment on the account, on the day it is booked. */
    record Booking(LocalDate date, String reference, CreditDebit direction, BigDecimal amount, String uetr,
            String endToEndId) {

        Booking on(LocalDate otherDate) {
            return new Booking(otherDate, reference, direction, amount, uetr, endToEndId);
        }

        BigDecimal signedAmount() {
            return direction == CreditDebit.CRDT ? amount : amount.negate();
        }
    }

    /** A day of the account: its opening balance and its bookings. */
    record Day(LocalDate date, BigDecimal openingBalance, List<Booking> bookings) {

        Day {
            bookings = List.copyOf(bookings);
        }

        BigDecimal closingBalance() {
            BigDecimal balance = openingBalance;
            for (Booking booking : bookings) {
                balance = balance.add(booking.signedAmount());
            }
            return balance;
        }
    }
}
