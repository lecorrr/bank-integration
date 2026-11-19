package dev.tkachuk.integration.incoming;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One payment from another bank to a customer of bank-core, as IncomingPayments keeps it (table incoming_payment).
 *
 * @param uetr           the payment's unique end-to-end reference, by which it is kept
 * @param senderBic      the bank that sent the pacs.008, its debtor agent; a MsgId is unique per sending bank
 * @param messageId      the pacs.008's MsgId, by which our answers name the payment
 * @param pacs008        the pacs.008 as it came
 * @param status         our answer, ACSC or RJCT; null while bank-core decides
 * @param reasonCode     why we refused the payment; null unless RJCT
 * @param reportId       the MsgId of the pacs.002 that carries our answer
 * @param answeredAt     when we answered, to the second, as the pacs.002 says
 * @param bankTransferId bank-core's transfer for the payment; null for one our bank refused itself
 */
record IncomingPayment(
        UUID uetr,
        String senderBic,
        String messageId,
        String pacs008,
        Instant receivedAt,
        TransactionStatus status,
        String reasonCode,
        String reportId,
        Instant answeredAt,
        UUID bankTransferId) {

    /** A payment that has just come, for bank-core to decide on. */
    static IncomingPayment received(Pacs008 payment, String xml, Instant receivedAt) {
        return new IncomingPayment(UUID.fromString(payment.uetr()), payment.debtorAgentBic(), payment.messageId(), xml,
                receivedAt, null, null, null, null, null);
    }

    /** A payment that has just come, refused by our bank itself in a pacs.002 with this id. */
    static IncomingPayment refused(Pacs008 payment, String xml, Instant receivedAt, String reasonCode,
            String reportId) {
        return new IncomingPayment(UUID.fromString(payment.uetr()), payment.debtorAgentBic(), payment.messageId(), xml,
                receivedAt, TransactionStatus.RJCT, reasonCode, reportId, receivedAt, null);
    }

    boolean answered() {
        return status != null;
    }

    /**
     * Our answer, the same pacs.002 every time it goes out: the same MsgId, so a bank that has it already can drop
     * the copy, and the same time.
     */
    Pacs002 answer() {
        if (!answered()) {
            throw new IllegalStateException("Payment " + uetr + " has no answer yet");
        }
        Pacs008 payment = Pacs008.parse(pacs008);
        return status == TransactionStatus.RJCT
                ? Pacs002.rejecting(payment, reasonCode, reportId, answeredAt)
                : Pacs002.answering(payment, status, reportId, answeredAt);
    }

    /** Whether our answer is the one bank-core's decision gives, on the transfer bank-core made of the payment. */
    boolean answers(IncomingTransferDecided decision) {
        return status == decision.status()
                && Objects.equals(reasonCode, decision.reasonCode())
                && decision.transferId().equals(bankTransferId);
    }

    /** E.g. "RJCT AC01", or "ACSC". */
    String outcome() {
        return reasonCode == null ? String.valueOf(status) : status + " " + reasonCode;
    }
}
