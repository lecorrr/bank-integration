package dev.tkachuk.integration.incoming;

import dev.tkachuk.integration.screening.Screening;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Table incoming_payment: one row per UETR, answered once. */
@Component
class IncomingPayments {

    private final JdbcClient jdbc;

    IncomingPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<IncomingPayment> insertIfAbsent(IncomingPayment payment) {
        jdbc.sql("""
                        INSERT INTO incoming_payment (uetr, sender_bic, message_id, pacs008, settlement_date, amount,
                            currency, received_at, status, reason_code, report_id, answered_at)
                        VALUES (:uetr, :senderBic, :messageId, :pacs008, :settlementDate, :amount, :currency,
                            :receivedAt, :status, :reasonCode, :reportId, :answeredAt)
                        ON CONFLICT DO NOTHING""")
                .param("uetr", payment.uetr())
                .param("senderBic", payment.senderBic())
                .param("messageId", payment.messageId())
                .param("pacs008", payment.pacs008())
                .param("settlementDate", payment.settlementDate())
                .param("amount", payment.amount())
                .param("currency", payment.currency())
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

    Optional<IncomingPayment> findByMessageId(String senderBic, String messageId) {
        return jdbc.sql("SELECT * FROM incoming_payment WHERE sender_bic = :senderBic AND message_id = :messageId")
                .param("senderBic", senderBic)
                .param("messageId", messageId)
                .query(IncomingPayments::fromRow)
                .optional();
    }

    IncomingPayment recordCleared(UUID uetr, Screening screening) {
        if (!screening.cleared()) {
            throw new IllegalArgumentException("Screening " + screening.screeningId() + " did not clear the payment");
        }
        jdbc.sql("""
                        UPDATE incoming_payment
                        SET screening_outcome = 'CLEAR', screening_id = :screeningId, screened_at = :screenedAt
                        WHERE uetr = :uetr AND screening_outcome IS NULL AND status IS NULL""")
                .param("uetr", uetr)
                .param("screeningId", screening.screeningId())
                .param("screenedAt", utc(screening.screenedAt()))
                .update();
        return storedAfterScreening(uetr);
    }

    IncomingPayment recordSanctionsRefusal(UUID uetr, Screening screening, String reportId, Instant answeredAt) {
        if (screening.cleared()) {
            throw new IllegalArgumentException("Screening " + screening.screeningId() + " cleared the payment");
        }
        jdbc.sql("""
                        UPDATE incoming_payment
                        SET screening_outcome = 'HIT', screening_id = :screeningId, screened_at = :screenedAt,
                            status = 'RJCT', reason_code = :reasonCode, report_id = :reportId,
                            answered_at = :answeredAt
                        WHERE uetr = :uetr AND screening_outcome IS NULL AND status IS NULL""")
                .param("uetr", uetr)
                .param("screeningId", screening.screeningId())
                .param("screenedAt", utc(screening.screenedAt()))
                .param("reasonCode", StatusReason.REGULATORY_REASON)
                .param("reportId", reportId)
                .param("answeredAt", utc(answeredAt))
                .update();
        return storedAfterScreening(uetr);
    }

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

    private IncomingPayment storedAfterScreening(UUID uetr) {
        return find(uetr).orElseThrow(() -> new IllegalStateException(
                "Payment " + uetr + " was screened, but it is not stored"));
    }

    private static IncomingPayment fromRow(ResultSet row, int rowNumber) throws SQLException {
        String status = row.getString("status");
        return new IncomingPayment(
                row.getObject("uetr", UUID.class),
                row.getString("sender_bic"),
                row.getString("message_id"),
                row.getString("pacs008"),
                row.getObject("settlement_date", LocalDate.class),
                row.getBigDecimal("amount"),
                row.getString("currency"),
                instantOf(row, "received_at"),
                status == null ? null : TransactionStatus.valueOf(status),
                row.getString("reason_code"),
                row.getString("report_id"),
                instantOf(row, "answered_at"),
                row.getObject("bank_transfer_id", UUID.class),
                Screening.stored(row.getString("screening_outcome"), row.getString("screening_id"),
                        instantOf(row, "screened_at")));
    }

    private static Instant instantOf(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
