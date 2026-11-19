package dev.tkachuk.integration.outgoing;

import dev.tkachuk.interbank.iso20022.Pacs002;
import java.time.Instant;
import java.util.UUID;

/**
 * What became of a transfer, for bank-core to complete or reverse it (step 3). Published to {@value #TOPIC},
 * keyed by the transfer id. As JSON:
 *
 * <pre>
 * {"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e", "status": "SETTLED",
 *  "reportId": "67a65fa611fe4db9bc67b8271c441990", "reportedAt": "2026-11-17T08:30:01Z"}
 * </pre>
 *
 * @param reportId   the MsgId of the other bank's pacs.002; for a transfer our bank refused to send, the id this
 *                   service gave the refusal
 * @param reportedAt when the pacs.002 was written, or the refusal made
 */
public record TransferStatusChanged(UUID transferId, TransferStatus status, String reportId, Instant reportedAt) {

    public static final String TOPIC = "interbank.transfer-statuses";

    public TransferStatusChanged {
        if (transferId == null || status == null || reportId == null || reportedAt == null) {
            throw new IllegalArgumentException("transferId, status, reportId and reportedAt are all required");
        }
    }

    /** What the other bank's report means for the transfer, under the report's own id and time. */
    static TransferStatusChanged reported(UUID transferId, TransferStatus status, Pacs002 report) {
        return new TransferStatusChanged(transferId, status, report.messageId(), report.createdAt());
    }
}
