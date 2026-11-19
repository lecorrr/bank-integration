package dev.tkachuk.integration.incoming;

import dev.tkachuk.integration.screening.Screening;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** A row of incoming_payment. */
record IncomingPayment(
        UUID uetr,
        String senderBic,
        String messageId,
        String pacs008,
        LocalDate settlementDate,
        BigDecimal amount,
        String currency,
        Instant receivedAt,
        TransactionStatus status,
        String reasonCode,
        String reportId,
        Instant answeredAt,
        UUID bankTransferId,
        Screening screening) {

    static IncomingPayment received(Pacs008 payment, String xml, Instant receivedAt) {
        return new IncomingPayment(UUID.fromString(payment.uetr()), payment.debtorAgentBic(), payment.messageId(), xml,
                payment.settlementDate(), payment.amount(), payment.currency(), receivedAt, null, null, null, null,
                null, null);
    }

    static IncomingPayment refused(Pacs008 payment, String xml, Instant receivedAt, String reasonCode,
            String reportId) {
        return new IncomingPayment(UUID.fromString(payment.uetr()), payment.debtorAgentBic(), payment.messageId(), xml,
                payment.settlementDate(), payment.amount(), payment.currency(), receivedAt, TransactionStatus.RJCT,
                reasonCode, reportId, receivedAt, null, null);
    }

    boolean answered() {
        return status != null;
    }

    /** Our answer rebuilt from what is stored: the same MsgId and time every time it goes out. */
    Pacs002 answer() {
        if (!answered()) {
            throw new IllegalStateException("Payment " + uetr + " has no answer yet");
        }
        Pacs008 payment = Pacs008.parse(pacs008);
        return status == TransactionStatus.RJCT
                ? Pacs002.rejecting(payment, reasonCode, reportId, answeredAt)
                : Pacs002.answering(payment, status, reportId, answeredAt);
    }

    boolean answers(IncomingTransferDecided decision) {
        return status == decision.status()
                && Objects.equals(reasonCode, decision.reasonCode())
                && decision.transferId().equals(bankTransferId);
    }

    String outcome() {
        return reasonCode == null ? String.valueOf(status) : status + " " + reasonCode;
    }
}
