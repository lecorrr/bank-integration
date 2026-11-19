package dev.tkachuk.integration.outgoing;

import dev.tkachuk.interbank.iso20022.Pacs002;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The transfers to other banks, in table outgoing_payment: each one sent, or refused by our bank. Each change is
 * one conditional UPDATE that names the states it may start from, so the routes, which run side by side, cannot
 * undo each other's work: a "sent" that arrives after the answer leaves the answer standing, and nothing changes
 * a final answer.
 */
@Component
class OutgoingPayments {

    private final JdbcClient jdbc;

    OutgoingPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the payment as NEW, unless its transfer has one already, and returns the payment as stored: for a
     * repeated event, the first event's message and state.
     */
    OutgoingPayment insertIfAbsent(UUID transferId, String messageId, String pacs008, Instant now) {
        jdbc.sql("""
                        INSERT INTO outgoing_payment (transfer_id, message_id, pacs008, state, created_at)
                        VALUES (:transferId, :messageId, :pacs008, 'NEW', :now)
                        ON CONFLICT DO NOTHING""")
                .param("transferId", transferId)
                .param("messageId", messageId)
                .param("pacs008", pacs008)
                .param("now", utc(now))
                .update();
        // Only a message id taken by another transfer leaves no row; ids come from the transfer id, so never.
        return find(transferId).orElseThrow(() -> new IllegalStateException(
                "Transfer " + transferId + " was not stored: its message id " + messageId + " is taken"));
    }

    /**
     * Our bank's own refusal to send the transfer, kept as its final answer under the refusal's id, so a repeated
     * event is not decided again, not even after a change to the directory of banks.
     */
    void recordRefusal(UUID transferId, String messageId, String refusalId, Instant now) {
        jdbc.sql("""
                        INSERT INTO outgoing_payment
                            (transfer_id, message_id, state, created_at, report_id, answered_at)
                        VALUES (:transferId, :messageId, 'REJECTED', :now, :refusalId, :now)
                        ON CONFLICT DO NOTHING""")
                .param("transferId", transferId)
                .param("messageId", messageId)
                .param("refusalId", refusalId)
                .param("now", utc(now))
                .update();
    }

    Optional<OutgoingPayment> find(UUID transferId) {
        return jdbc.sql("SELECT * FROM outgoing_payment WHERE transfer_id = :transferId")
                .param("transferId", transferId)
                .query(OutgoingPayments::fromRow)
                .optional();
    }

    Optional<OutgoingPayment> findByMessageId(String messageId) {
        return jdbc.sql("SELECT * FROM outgoing_payment WHERE message_id = :messageId")
                .param("messageId", messageId)
                .query(OutgoingPayments::fromRow)
                .optional();
    }

    /** The pacs.008 went out, the first time or again: the status requests start over, the first at firstCheckAt. */
    void recordSent(UUID transferId, Instant sentAt, Instant firstCheckAt) {
        jdbc.sql("""
                        UPDATE outgoing_payment
                        SET state = 'SENT', sent_at = :sentAt, times_sent = times_sent + 1, status_requests = 0,
                            next_check_at = :firstCheckAt
                        WHERE transfer_id = :transferId AND state IN ('NEW', 'SENT', 'PENDING')""")
                .param("transferId", transferId)
                .param("sentAt", utc(sentAt))
                .param("firstCheckAt", utc(firstCheckAt))
                .update();
    }

    /** PDNG: the other bank has the payment and has not decided. */
    void recordPending(UUID transferId) {
        jdbc.sql("""
                        UPDATE outgoing_payment SET state = 'PENDING'
                        WHERE transfer_id = :transferId AND state IN ('NEW', 'SENT')""")
                .param("transferId", transferId)
                .update();
    }

    /**
     * The final answer and the report that gave it; nothing to ask about after that.
     *
     * @return false when the payment had a final answer already, which then stands
     */
    boolean recordFinal(UUID transferId, PaymentState state, Pacs002 report, Instant now) {
        if (!state.isFinal()) {
            throw new IllegalArgumentException(state + " is not a final answer");
        }
        int updated = jdbc.sql("""
                        UPDATE outgoing_payment
                        SET state = :state, report_id = :reportId, reason_code = :reasonCode, answered_at = :now,
                            next_check_at = NULL
                        WHERE transfer_id = :transferId AND state IN ('NEW', 'SENT', 'PENDING')""")
                .param("transferId", transferId)
                .param("state", state.name())
                .param("reportId", report.messageId())
                .param("reasonCode", report.reasonCode())
                .param("now", utc(now))
                .update();
        return updated == 1;
    }

    /** Due a status request now, ahead of its schedule. */
    void askNow(UUID transferId, Instant now) {
        jdbc.sql("""
                        UPDATE outgoing_payment SET next_check_at = :now
                        WHERE transfer_id = :transferId AND state IN ('SENT', 'PENDING')""")
                .param("transferId", transferId)
                .param("now", utc(now))
                .update();
    }

    /**
     * Up to limit payments whose status request is due, the longest due first, each held back until leaseUntil:
     * its next check moves there, so a second instance of the service, or this one after a crash, does not ask
     * about it meanwhile. SKIP LOCKED lets two instances claim at once without waiting for each other. Only open
     * payments have a next check at all; the state condition is there because it is the partial index's own, so
     * PostgreSQL can use that index.
     */
    List<OutgoingPayment> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE outgoing_payment SET next_check_at = :leaseUntil
                        WHERE transfer_id IN (
                            SELECT transfer_id FROM outgoing_payment
                            WHERE state IN ('SENT', 'PENDING') AND next_check_at <= :now
                            ORDER BY next_check_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING *""")
                .param("now", utc(now))
                .param("leaseUntil", utc(leaseUntil))
                .param("limit", limit)
                .query(OutgoingPayments::fromRow)
                .list();
    }

    /**
     * A status request went out about the payment as it was claimed: one more, and the next at nextCheckAt.
     * Skipped if the payment went out again meanwhile (its timesSent moved on), whose requests then started over.
     */
    void recordStatusRequest(UUID transferId, int timesSent, Instant nextCheckAt) {
        jdbc.sql("""
                        UPDATE outgoing_payment SET status_requests = status_requests + 1, next_check_at = :nextCheckAt
                        WHERE transfer_id = :transferId AND state IN ('SENT', 'PENDING') AND times_sent = :timesSent""")
                .param("transferId", transferId)
                .param("timesSent", timesSent)
                .param("nextCheckAt", utc(nextCheckAt))
                .update();
    }

    private static OutgoingPayment fromRow(ResultSet row, int rowNumber) throws SQLException {
        return new OutgoingPayment(
                row.getObject("transfer_id", UUID.class),
                row.getString("message_id"),
                row.getString("pacs008"),
                PaymentState.valueOf(row.getString("state")),
                instantOf(row, "created_at"),
                instantOf(row, "sent_at"),
                row.getInt("times_sent"),
                row.getInt("status_requests"),
                instantOf(row, "next_check_at"),
                row.getString("report_id"),
                row.getString("reason_code"),
                instantOf(row, "answered_at"));
    }

    private static Instant instantOf(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** The PostgreSQL driver binds an OffsetDateTime to timestamptz; it has no binding for Instant. */
    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
