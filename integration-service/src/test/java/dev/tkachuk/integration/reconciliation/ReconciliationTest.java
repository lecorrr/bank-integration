package dev.tkachuk.integration.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-17");

    private final Reconciliation.Item matched = line(MatchResult.MATCHED);
    private final Reconciliation.Item mismatch = line(MatchResult.AMOUNT_MISMATCH);
    private final Reconciliation.Item missing = Reconciliation.Item.missing(
            new UnbookedPayment(UUID.randomUUID(), CreditDebit.CRDT, new BigDecimal("250.00"), "UAH"));

    @Test
    void shouldListBreaksInOrder() {
        Reconciliation reconciliation = new Reconciliation(statement("1000.00"), null,
                List.of(matched, mismatch, missing));

        assertThat(reconciliation.breaks()).containsExactly(mismatch, missing);
        assertThat(reconciliation.lines()).as("lines of the statement, not missing payments").isEqualTo(2);
    }

    @Test
    void shouldKeepMissingPaymentWithoutBooking() {
        assertThat(missing.result()).isEqualTo(MatchResult.MISSING);
        assertThat(missing.reference()).isNull();
        assertThat(missing.bookedAmount()).isNull();
        assertThat(missing.ourAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    void shouldContinueWithoutPreviousStatement() {
        assertThat(new Reconciliation(statement("1000.00"), null, List.of()).continuesPrevious()).isTrue();
    }

    @Test
    void shouldContinueFromPreviousClosingBalance() {
        StoredStatement previous = new StoredStatement("STATEMENT-0", DAY.minusDays(1), "UAH",
                new BigDecimal("900.00"), new BigDecimal("1000.0"));

        assertThat(new Reconciliation(statement("1000.00"), previous, List.of()).continuesPrevious()).isTrue();
        assertThat(new Reconciliation(statement("999.99"), previous, List.of()).continuesPrevious()).isFalse();
    }

    private static StoredStatement statement(String openingBalance) {
        return new StoredStatement("STATEMENT-1", DAY, "UAH", new BigDecimal(openingBalance),
                new BigDecimal(openingBalance));
    }

    private static Reconciliation.Item line(MatchResult result) {
        StatementLine line = new StatementLine(1, "PRTB0000000001", CreditDebit.DBIT, new BigDecimal("150.00"),
                UUID.randomUUID(), false, true, true, new BigDecimal("150.00"), "UAH");
        return Reconciliation.Item.of(line, result);
    }
}
