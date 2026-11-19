package dev.tkachuk.integration.outgoing;

import java.time.Instant;
import java.util.UUID;

/**
 * One payment to another bank, as OutgoingPayments keeps it (table outgoing_payment).
 *
 * @param messageId      the pacs.008's MsgId, by which the other bank's answers name the payment
 * @param pacs008        the pacs.008 as it first went out, which a resend repeats unchanged; null for a transfer
 *                       our bank refused to send
 * @param sentAt         when the pacs.008 last went out; null while NEW
 * @param timesSent      how many times it went out
 * @param statusRequests the status requests (pacs.028) since it last went out
 * @param nextCheckAt    when the next status request is due; null while NEW and once the answer is final
 * @param reportId       the MsgId of the pacs.002 that gave the final answer, or our own refusal's id
 * @param reasonCode     that pacs.002's reason code, if it gave one
 * @param answeredAt     when the final answer came
 */
record OutgoingPayment(
        UUID transferId,
        String messageId,
        String pacs008,
        PaymentState state,
        Instant createdAt,
        Instant sentAt,
        int timesSent,
        int statusRequests,
        Instant nextCheckAt,
        String reportId,
        String reasonCode,
        Instant answeredAt) {
}
