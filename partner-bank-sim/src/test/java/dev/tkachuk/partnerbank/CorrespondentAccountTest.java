package dev.tkachuk.partnerbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrespondentAccountTest {

    // 10:00 in Kyiv
    private static final Instant MORNING = Instant.parse("2026-11-17T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-17");

    private final TestClock clock = new TestClock(MORNING);
    private final CorrespondentAccount account = new CorrespondentAccount(clock, new BigDecimal("1000.00"), 100);

    @Test
    void shouldOpenOnTodayInKyiv() {
        TestClock justAfterMidnight = new TestClock(Instant.parse("2026-11-16T22:30:00Z"));

        CorrespondentAccount opened = new CorrespondentAccount(justAfterMidnight, BigDecimal.ZERO, 100);

        assertThat(opened.openDay().date()).isEqualTo(TODAY);
    }

    @Test
    void shouldDebitAndCreditAcceptedPayments() {
        String outgoing = UUID.randomUUID().toString();
        String incoming = UUID.randomUUID().toString();

        account.debit(TestPayments.fromBankCore(outgoing, "150.00"));
        account.credit(TestPayments.toBankCore(incoming));

        CorrespondentAccount.Day day = account.openDay();
        assertThat(day.date()).isEqualTo(TODAY);
        assertThat(day.bookings()).extracting(CorrespondentAccount.Booking::uetr).containsExactly(outgoing, incoming);
        assertThat(day.bookings()).extracting(CorrespondentAccount.Booking::direction)
                .containsExactly(CreditDebit.DBIT, CreditDebit.CRDT);
        assertThat(day.closingBalance()).isEqualByComparingTo("1100.00");
    }

    @Test
    void shouldBookPaymentOnce() {
        String uetr = UUID.randomUUID().toString();

        account.debit(TestPayments.fromBankCore(uetr, "150.00"));
        account.debit(TestPayments.fromBankCore(uetr, "150.00"));

        assertThat(account.openDay().bookings()).hasSize(1);
    }

    @Test
    void shouldStartNextDayFromClosingBalance() {
        account.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "150.00"));

        account.close(account.openDay());

        CorrespondentAccount.Day next = account.openDay();
        assertThat(next.date()).isEqualTo(TODAY.plusDays(1));
        assertThat(next.openingBalance()).isEqualByComparingTo("850.00");
        assertThat(next.bookings()).isEmpty();
    }

    @Test
    void shouldMoveBookingMadeWhileStatementWasSentToNextDay() {
        account.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "150.00"));
        CorrespondentAccount.Day reported = account.openDay();
        String late = UUID.randomUUID().toString();
        account.debit(TestPayments.fromBankCore(late, "20.00"));

        account.close(reported);

        CorrespondentAccount.Day next = account.openDay();
        assertThat(next.openingBalance()).isEqualByComparingTo("850.00");
        assertThat(next.bookings()).singleElement().satisfies(booking -> {
            assertThat(booking.uetr()).isEqualTo(late);
            assertThat(booking.date()).isEqualTo(TODAY.plusDays(1));
        });
    }

    @Test
    void shouldBookOnTodayWhenOpenDayIsOver() {
        clock.set(MORNING.plusSeconds(24 * 3600));

        account.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "150.00"));

        assertThat(account.openDay().bookings()).as("bookings of the day still open").isEmpty();
        account.close(account.openDay());
        assertThat(account.openDay().bookings()).singleElement()
                .satisfies(booking -> assertThat(booking.date()).isEqualTo(TODAY.plusDays(1)));
    }

    @Test
    void shouldNotCloseDayThatIsNotOpen() {
        CorrespondentAccount.Day day = account.openDay();
        account.close(day);

        assertThatThrownBy(() -> account.close(day))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not the open day");
    }

    @Test
    void shouldNotBookPaymentInAnotherCurrency() {
        account.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "150.00", "USD"));

        assertThat(account.openDay().bookings()).isEmpty();
    }

    @Test
    void shouldForgetOldestBookingWhenFull() {
        CorrespondentAccount small = new CorrespondentAccount(clock, BigDecimal.ZERO, 2);
        String oldest = UUID.randomUUID().toString();
        small.debit(TestPayments.fromBankCore(oldest, "1.00"));
        small.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "2.00"));
        small.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "3.00"));

        small.debit(TestPayments.fromBankCore(oldest, "1.00"));

        assertThat(small.openDay().bookings()).as("the oldest one booked again").hasSize(4);
    }

    @Test
    void shouldGiveEveryBookingItsOwnReference() {
        account.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "1.00"));
        account.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "2.00"));

        assertThat(account.openDay().bookings()).extracting(CorrespondentAccount.Booking::reference)
                .doesNotHaveDuplicates()
                .allMatch(reference -> reference.matches("PRTB[0-9]{10}"));
    }
}
