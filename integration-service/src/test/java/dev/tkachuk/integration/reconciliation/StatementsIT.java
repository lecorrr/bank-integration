package dev.tkachuk.integration.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.TestDatabase;
import dev.tkachuk.interbank.iso20022.Camt053;
import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class StatementsIT {

    private static final String OUR_ACCOUNT = "UA853888880000000000000000399";
    // No other test settles payments on this day, so unbooked sees only this test's payments.
    private static final LocalDate DAY = LocalDate.parse("2026-09-17");
    private static final Instant RECEIVED_AT = Instant.parse("2026-09-17T22:00:00Z");

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final Statements statements = new Statements(jdbc);

    @BeforeEach
    void startWithoutStatements() {
        jdbc.sql("DELETE FROM statement_entry").update();
        jdbc.sql("DELETE FROM statement").update();
    }

    @Test
    void shouldStoreStatementOnce() {
        Camt053 statement = statement("S-1", DAY, List.of(entry("REF-1", CreditDebit.DBIT, UUID.randomUUID())));

        assertThat(statements.store(statement, "<camt053/>", "s-1.xml", RECEIVED_AT)).isTrue();
        assertThat(statements.store(statement, "<camt053/>", "s-1-again.xml", RECEIVED_AT)).isFalse();

        assertThat(statements.find(OUR_ACCOUNT, DAY)).contains(new StoredStatement("S-1", DAY, "UAH",
                new BigDecimal("1000.00"), new BigDecimal("850.00")));
        assertThat(statements.lines("S-1")).hasSize(1);
    }

    @Test
    void shouldRefuseSecondStatementForDay() {
        statements.store(statement("S-1", DAY, List.of()), "<camt053/>", "s-1.xml", RECEIVED_AT);

        assertThatThrownBy(() -> statements.store(statement("S-2", DAY, List.of()), "<camt053/>", "s-2.xml",
                RECEIVED_AT))
                .isInstanceOf(UnusableStatementException.class)
                .hasMessageContaining("second statement for 2026-09-17");
        assertThat(statements.find(OUR_ACCOUNT, DAY).orElseThrow().messageId()).isEqualTo("S-1");
    }

    @Test
    void shouldReadEachLineWithOurSideOfPayment() {
        UUID settled = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outgoing_payment (transfer_id, message_id, pacs008, state, created_at,
                            settlement_date, amount, currency)
                        VALUES (:transferId, :messageId, '<pacs008/>', 'SETTLED', now(), :day, 150.00, 'UAH')""")
                .param("transferId", settled)
                .param("messageId", settled.toString().replace("-", ""))
                .param("day", DAY)
                .update();
        UUID unknown = UUID.randomUUID();
        statements.store(statement("S-1", DAY, List.of(entry("REF-1", CreditDebit.DBIT, settled),
                entry("REF-2", CreditDebit.CRDT, unknown))), "<camt053/>", "s-1.xml", RECEIVED_AT);

        List<StatementLine> lines = statements.lines("S-1");

        assertThat(lines).containsExactly(
                new StatementLine(1, "REF-1", CreditDebit.DBIT, new BigDecimal("150.00"), settled, false, true, true,
                        new BigDecimal("150.00"), "UAH"),
                new StatementLine(2, "REF-2", CreditDebit.CRDT, new BigDecimal("150.00"), unknown, false, false,
                        false, null, null));
    }

    @Test
    void shouldListPaymentsSettledOnDayThatNoStatementBooks() {
        UUID booked = acceptedIncoming(DAY);
        UUID notBooked = acceptedIncoming(DAY);
        acceptedIncoming(DAY.plusDays(1));
        statements.store(statement("S-1", DAY, List.of(entry("REF-1", CreditDebit.CRDT, booked))), "<camt053/>",
                "s-1.xml", RECEIVED_AT);

        assertThat(statements.unbooked(DAY)).containsExactly(
                new UnbookedPayment(notBooked, CreditDebit.CRDT, new BigDecimal("250.00"), "UAH"));
    }

    @Test
    void shouldFindLatestEarlierStatement() {
        statements.store(statement("S-1", DAY.minusDays(3), List.of()), "<camt053/>", "s-1.xml", RECEIVED_AT);
        statements.store(statement("S-2", DAY.minusDays(1), List.of()), "<camt053/>", "s-2.xml", RECEIVED_AT);
        statements.store(statement("S-3", DAY, List.of()), "<camt053/>", "s-3.xml", RECEIVED_AT);

        assertThat(statements.findPrevious(OUR_ACCOUNT, DAY).orElseThrow().messageId()).isEqualTo("S-2");
        assertThat(statements.findPrevious(OUR_ACCOUNT, DAY.minusDays(3))).isEmpty();
    }

    private UUID acceptedIncoming(LocalDate settlementDate) {
        UUID uetr = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO incoming_payment (uetr, sender_bic, message_id, pacs008, settlement_date, amount,
                            currency, received_at, status, report_id, answered_at)
                        VALUES (:uetr, 'PRTBUAUKXXX', :messageId, '<pacs008/>', :day, 250.00, 'UAH', now(), 'ACSC',
                            'ANSWER-1', now())""")
                .param("uetr", uetr)
                .param("messageId", uetr.toString().replace("-", ""))
                .param("day", settlementDate)
                .update();
        return uetr;
    }

    private static Camt053 statement(String messageId, LocalDate date, List<Camt053.Entry> entries) {
        BigDecimal closingBalance = new BigDecimal("1000.00");
        for (Camt053.Entry entry : entries) {
            closingBalance = closingBalance.add(entry.signedAmount());
        }
        return new Camt053(messageId, RECEIVED_AT, "STMT-" + date, OUR_ACCOUNT, "PRTBUAUKXXX", "UAH", date,
                new BigDecimal("1000.00"), closingBalance, entries);
    }

    private static Camt053.Entry entry(String reference, CreditDebit direction, UUID uetr) {
        return new Camt053.Entry(reference, direction, new BigDecimal("150.00"), uetr.toString(), "NOTPROVIDED");
    }
}
