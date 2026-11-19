package dev.tkachuk.partnerbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.interbank.iso20022.Camt053;
import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatementWriterTest {

    // 23:55 in Kyiv
    private static final Instant NOW = Instant.parse("2026-11-17T21:55:00Z");

    private final TestClock clock = new TestClock(NOW);
    private final CorrespondentAccount account = new CorrespondentAccount(clock, new BigDecimal("1000.00"), 100);
    private final StatementWriter writer =
            new StatementWriter(account, TestStatements.PROPERTIES, TestStatements.SENDING, clock);
    private final String rent = UUID.randomUUID().toString();
    private final String concert = UUID.randomUUID().toString();

    @BeforeEach
    void bookTwoPayments() {
        account.debit(TestPayments.fromBankCore(rent, "150.00"));
        account.credit(TestPayments.toBankCore(concert));
    }

    @Test
    void shouldWriteEveryBookingOfTheDay() {
        Camt053 statement = writer.write(account.openDay(), StatementFaults.NONE);

        assertThat(statement.accountIban()).isEqualTo(TestStatements.ACCOUNT);
        assertThat(statement.accountServicerBic()).isEqualTo("PRTBUAUKXXX");
        assertThat(statement.date()).isEqualTo(LocalDate.parse("2026-11-17"));
        assertThat(statement.statementId()).isEqualTo("STMT-20261117");
        assertThat(statement.createdAt()).isEqualTo(NOW);
        assertThat(statement.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("1100.00");
        assertThat(statement.entries()).extracting(Camt053.Entry::uetr).containsExactly(rent, concert);
        assertThat(Camt053.parse(statement.toXml())).isEqualTo(statement);
    }

    @Test
    void shouldPutRequestedFaultsInStatement() {
        String coffee = UUID.randomUUID().toString();
        account.debit(TestPayments.fromBankCore(coffee, "30.00"));
        StatementFaults faults = new StatementFaults(List.of(rent), Map.of(concert, new BigDecimal("249.00")),
                List.of(coffee), List.of(new StatementFaults.Unknown(CreditDebit.DBIT, new BigDecimal("10.00"))));

        Camt053 statement = writer.write(account.openDay(), faults);

        assertThat(statement.entries()).extracting(Camt053.Entry::amount).containsExactly(new BigDecimal("249.00"),
                new BigDecimal("30.00"), new BigDecimal("30.00"), new BigDecimal("10.00"));
        assertThat(statement.entries()).extracting(Camt053.Entry::uetr).doesNotContain(rent)
                .containsSequence(concert, coffee, coffee);
        assertThat(statement.entries().getLast().uetr()).isNotIn(rent, concert, coffee);
        assertThat(statement.closingBalance()).as("what the entries add up to").isEqualByComparingTo("1179.00");
    }

    @Test
    void shouldFindPaymentWhateverCaseItsUetrIsIn() {
        StatementFaults faults = new StatementFaults(List.of(rent.toUpperCase(Locale.ROOT)), null, null, null);

        Camt053 statement = writer.write(account.openDay(), faults);

        assertThat(statement.entries()).extracting(Camt053.Entry::uetr).containsExactly(concert);
    }

    @Test
    void shouldRefuseFaultForPaymentNotBookedThatDay() {
        StatementFaults faults = new StatementFaults(null, null, List.of(UUID.randomUUID().toString()), null);

        assertThatThrownBy(() -> writer.write(account.openDay(), faults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not booked on 2026-11-17");
    }
}
