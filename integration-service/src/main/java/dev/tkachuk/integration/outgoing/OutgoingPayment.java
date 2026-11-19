package dev.tkachuk.integration.outgoing;

import dev.tkachuk.integration.screening.Screening;
import java.time.Instant;
import java.util.UUID;

/** A row of outgoing_payment. */
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
        Instant answeredAt,
        Screening screening) {
}
