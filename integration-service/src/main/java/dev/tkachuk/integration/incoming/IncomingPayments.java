package dev.tkachuk.integration.incoming;

import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The payments from other banks to bank-core's customers, in table incoming_payment. A payment is stored once, by
 * its UETR, and answered once: the answer is a conditional UPDATE that only a payment without one takes, so an
 * answer given stands.
 */
@Component
class IncomingPayments {

    private final JdbcClient jdbc;

    IncomingPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the payment, unless one with its UETR, or with its MsgId from the same bank, is stored already.
     *
     * @return the payment stored under its UETR, whichever message stored it first; none when its sending bank's
     *         MsgId is taken by another payment
     */
    Optional<IncomingPayment> insertIfAbsent(IncomingPayment payment) {
        jdbc.sql("""
                        INSERT INTO incoming_payment (uetr, sender_bic, message_id, pacs008, received_at, status,
                            reason_code, report_id, answered_at)
                        VALUES (:uetr, :senderBic, :messageId, :pacs008, :receivedAt, :status, :reasonCode, :reportId,
                            :answeredAt)
                        ON CONFLICT DO NOTHING""")
                .param("uetr", payment.uetr())
                .param("senderBic", payment.senderBic())
                .param("messageId", payment.messageId())
                .param("pacs008", payment.pacs008())
                .param("receivedAt", utc(payment.receivedAt()))
                .param("status", payment.status() == null ? null : payment.status().name())
                .param("reasonCode", payment.reasonCode())
                .param("reportId", payment.reportId())
                .param("answeredAt", utc(payment.answeredAt()))
                .update();
        return find(payment.uetr());
    }

    Optional<IncomingPayment> find(UUID uetr) {
        return jdbc.sql("SELECT * FROM incoming_payment WHERE uetr = :uetr")
                .param("uetr", uetr)
                .query(IncomingPayments::fromRow)
                .optional();
    }

    /** The payment the bank with this BIC sent under this MsgId. */
    Optional<IncomingPayment> findByMessageId(String senderBic, String messageId) {
        return jdbc.sql("SELECT * FROM incoming_payment WHERE sender_bic = :senderBic AND message_id = :messageId")
                .param("senderBic", senderBic)
                .param("messageId", messageId)
                .query(IncomingPayments::fromRow)
                .optional();
    }

    /**
     * Our answer, from bank-core's decision on its transfer bankTransferId; a payment answered already keeps its
     * answer.
     *
     * @return false when the payment had an answer already
     */
    boolean recordAnswer(UUID uetr, TransactionStatus status, String reasonCode, String reportId, Instant answeredAt,
            UUID bankTransferId) {
        int updated = jdbc.sql("""
                        UPDATE incoming_payment
                        SET status = :status, reason_code = :reasonCode, report_id = :reportId,
                            answered_at = :answeredAt, bank_transfer_id = :bankTransferId
                        WHERE uetr = :uetr AND status IS NULL""")
                .param("uetr", uetr)
                .param("status", status.name())
                .param("reasonCode", reasonCode)
                .param("reportId", reportId)
                .param("answeredAt", utc(answeredAt))
                .param("bankTransferId", bankTransferId)
                .update();
        return updated == 1;
    }

    private static IncomingPayment fromRow(ResultSet row, int rowNumber) throws SQLException {
        String status = row.getString("status");
        return new IncomingPayment(
                row.getObject("uetr", UUID.class),
                row.getString("sender_bic"),
                row.getString("message_id"),
                row.getString("pacs008"),
                instantOf(row, "received_at"),
                status == null ? null : TransactionStatus.valueOf(status),
                row.getString("reason_code"),
                row.getString("report_id"),
                instantOf(row, "answered_at"),
                row.getObject("bank_transfer_id", UUID.class));
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
