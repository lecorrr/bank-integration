package dev.tkachuk.integration.outgoing;

import dev.tkachuk.integration.screening.Screening;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.StatusReason;
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
 * Table outgoing_payment. Every update names the states it may start from, so routes running side by side can't
 * undo each other's work, and a final answer never changes.
 */
@Component
class OutgoingPayments {

    private final JdbcClient jdbc;

    OutgoingPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

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
        return find(transferId).orElseThrow(() -> new IllegalStateException(
                "Transfer " + transferId + " was not stored: its message id " + messageId + " is taken"));
    }

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

    OutgoingPayment recordScreening(UUID transferId, Screening screening) {
        jdbc.sql("""
                        UPDATE outgoing_payment
                        SET screening_outcome = :outcome, screening_id = :screeningId, screened_at = :screenedAt
                        WHERE transfer_id = :transferId AND screening_outcome IS NULL""")
                .param("transferId", transferId)
                .param("outcome", screening.outcome().name())
                .param("screeningId", screening.screeningId())
                .param("screenedAt", utc(screening.screenedAt()))
                .update();
        return find(transferId).orElseThrow(() -> new IllegalStateException(
                "Transfer " + transferId + " was screened, but it is not stored"));
    }

    boolean recordSanctionsRefusal(UUID transferId, String refusalId, Instant now) {
        int updated = jdbc.sql("""
                        UPDATE outgoing_payment
                        SET state = 'REJECTED', report_id = :refusalId, reason_code = :reasonCode, answered_at = :now
                        WHERE transfer_id = :transferId AND state = 'NEW' AND screening_outcome = 'HIT'""")
                .param("transferId", transferId)
                .param("refusalId", refusalId)
                .param("reasonCode", StatusReason.REGULATORY_REASON)
                .param("now", utc(now))
                .update();
        return updated == 1;
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

    void recordPending(UUID transferId) {
        jdbc.sql("""
                        UPDATE outgoing_payment SET state = 'PENDING'
                        WHERE transfer_id = :transferId AND state IN ('NEW', 'SENT')""")
                .param("transferId", transferId)
                .update();
    }

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

    void askNow(UUID transferId, Instant now) {
        jdbc.sql("""
                        UPDATE outgoing_payment SET next_check_at = :now
                        WHERE transfer_id = :transferId AND state IN ('SENT', 'PENDING')""")
                .param("transferId", transferId)
                .param("now", utc(now))
                .update();
    }

    /**
     * Claims due payments by moving their next check to leaseUntil. SKIP LOCKED lets several instances claim at
     * once without waiting for each other.
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

    // Skipped if the payment was sent again in the meantime (timesSent changed).
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
                instantOf(row, "answered_at"),
                Screening.stored(row.getString("screening_outcome"), row.getString("screening_id"),
                        instantOf(row, "screened_at")));
    }

    private static Instant instantOf(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // The PostgreSQL driver binds OffsetDateTime to timestamptz, but not Instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
