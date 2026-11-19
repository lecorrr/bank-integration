package dev.tkachuk.integration.reconciliation;

import dev.tkachuk.interbank.iso20022.Camt053;
import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tables statement and statement_entry, and the reads reconciliation needs next to them. It reads outgoing_payment
 * and incoming_payment too, but never changes them.
 */
@Component
class Statements {

    private final JdbcClient jdbc;

    Statements(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the statement with its entries, all or nothing. Returns false if this statement is stored already;
     * another statement for a day that has one is refused.
     */
    @Transactional
    boolean store(Camt053 statement, String xml, String fileName, Instant receivedAt) {
        int inserted = jdbc.sql("""
                        INSERT INTO statement (message_id, statement_id, account_iban, statement_date, currency,
                            opening_balance, closing_balance, created_at, camt053, file_name, received_at)
                        VALUES (:messageId, :statementId, :accountIban, :date, :currency, :openingBalance,
                            :closingBalance, :createdAt, :camt053, :fileName, :receivedAt)
                        ON CONFLICT DO NOTHING""")
                .param("messageId", statement.messageId())
                .param("statementId", statement.statementId())
                .param("accountIban", statement.accountIban())
                .param("date", statement.date())
                .param("currency", statement.currency())
                .param("openingBalance", statement.openingBalance())
                .param("closingBalance", statement.closingBalance())
                .param("createdAt", utc(statement.createdAt()))
                .param("camt053", xml)
                .param("fileName", fileName)
                .param("receivedAt", utc(receivedAt))
                .update();
        if (inserted == 0) {
            if (exists(statement.messageId())) {
                return false;
            }
            throw new UnusableStatementException("Statement " + statement.messageId() + " is a second statement for "
                    + statement.date() + "; the first one is kept");
        }
        List<Camt053.Entry> entries = statement.entries();
        for (int i = 0; i < entries.size(); i++) {
            Camt053.Entry entry = entries.get(i);
            jdbc.sql("""
                            INSERT INTO statement_entry (message_id, line, reference, direction, amount, uetr,
                                end_to_end_id)
                            VALUES (:messageId, :line, :reference, :direction, :amount, :uetr, :endToEndId)""")
                    .param("messageId", statement.messageId())
                    .param("line", i + 1)
                    .param("reference", entry.reference())
                    .param("direction", entry.creditDebit().name())
                    .param("amount", entry.amount())
                    .param("uetr", UUID.fromString(entry.uetr()))
                    .param("endToEndId", entry.endToEndId())
                    .update();
        }
        return true;
    }

    Optional<StoredStatement> find(String accountIban, LocalDate date) {
        return jdbc.sql("""
                        SELECT message_id, statement_date, currency, opening_balance, closing_balance
                        FROM statement
                        WHERE account_iban = :accountIban AND statement_date = :date""")
                .param("accountIban", accountIban)
                .param("date", date)
                .query(Statements::statementOf)
                .optional();
    }

    /** The latest statement before the day. */
    Optional<StoredStatement> findPrevious(String accountIban, LocalDate date) {
        return jdbc.sql("""
                        SELECT message_id, statement_date, currency, opening_balance, closing_balance
                        FROM statement
                        WHERE account_iban = :accountIban AND statement_date < :date
                        ORDER BY statement_date DESC
                        LIMIT 1""")
                .param("accountIban", accountIban)
                .param("date", date)
                .query(Statements::statementOf)
                .optional();
    }

    /** The statement's entries in their order, each with the payment it books on our side, if we have it. */
    List<StatementLine> lines(String messageId) {
        return jdbc.sql("""
                        SELECT e.line, e.reference, e.direction, e.amount, e.uetr,
                            EXISTS (SELECT 1
                                    FROM statement_entry earlier
                                    JOIN statement s2 ON s2.message_id = earlier.message_id
                                    WHERE earlier.uetr = e.uetr AND earlier.direction = e.direction
                                        AND s2.account_iban = s.account_iban
                                        AND (s2.statement_date < s.statement_date
                                            OR (earlier.message_id = e.message_id AND earlier.line < e.line)))
                                AS booked_before,
                            (o.transfer_id IS NOT NULL OR i.uetr IS NOT NULL) AS known,
                            COALESCE(o.state = 'SETTLED', i.status = 'ACSC', FALSE) AS settled,
                            COALESCE(o.amount, i.amount) AS our_amount,
                            COALESCE(o.currency, i.currency) AS our_currency
                        FROM statement s
                        JOIN statement_entry e ON e.message_id = s.message_id
                        LEFT JOIN outgoing_payment o ON e.direction = 'DBIT' AND o.transfer_id = e.uetr
                        LEFT JOIN incoming_payment i ON e.direction = 'CRDT' AND i.uetr = e.uetr
                        WHERE s.message_id = :messageId
                        ORDER BY e.line""")
                .param("messageId", messageId)
                .query(Statements::lineOf)
                .list();
    }

    /** Payments settled on our side on the day that no statement books, whichever day it is for. */
    List<UnbookedPayment> unbooked(LocalDate settlementDate) {
        return jdbc.sql("""
                        SELECT o.transfer_id AS uetr, 'DBIT' AS direction, o.amount, o.currency
                        FROM outgoing_payment o
                        WHERE o.state = 'SETTLED' AND o.settlement_date = :settlementDate
                            AND NOT EXISTS (SELECT 1 FROM statement_entry e
                                            WHERE e.uetr = o.transfer_id AND e.direction = 'DBIT')
                        UNION ALL
                        SELECT i.uetr, 'CRDT', i.amount, i.currency
                        FROM incoming_payment i
                        WHERE i.status = 'ACSC' AND i.settlement_date = :settlementDate
                            AND NOT EXISTS (SELECT 1 FROM statement_entry e
                                            WHERE e.uetr = i.uetr AND e.direction = 'CRDT')
                        ORDER BY direction, uetr""")
                .param("settlementDate", settlementDate)
                .query((row, rowNumber) -> new UnbookedPayment(row.getObject("uetr", UUID.class),
                        CreditDebit.valueOf(row.getString("direction")), row.getBigDecimal("amount"),
                        row.getString("currency")))
                .list();
    }

    private boolean exists(String messageId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM statement WHERE message_id = :messageId)")
                .param("messageId", messageId)
                .query(Boolean.class)
                .single();
    }

    private static StoredStatement statementOf(ResultSet row, int rowNumber) throws SQLException {
        return new StoredStatement(row.getString("message_id"), row.getObject("statement_date", LocalDate.class),
                row.getString("currency"), row.getBigDecimal("opening_balance"), row.getBigDecimal("closing_balance"));
    }

    private static StatementLine lineOf(ResultSet row, int rowNumber) throws SQLException {
        return new StatementLine(
                row.getInt("line"),
                row.getString("reference"),
                CreditDebit.valueOf(row.getString("direction")),
                row.getBigDecimal("amount"),
                row.getObject("uetr", UUID.class),
                row.getBoolean("booked_before"),
                row.getBoolean("known"),
                row.getBoolean("settled"),
                row.getBigDecimal("our_amount"),
                row.getString("our_currency"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
