package dev.tkachuk.integration.outgoing;

import dev.tkachuk.interbank.iso20022.Pacs002;
import java.time.Instant;
import java.util.UUID;

/**
 * What became of a transfer at the other bank, from that bank's pacs.002. Published to {@value #TOPIC}, keyed by
 * the transfer id, for bank-core to complete or reverse the transfer (step 3). As JSON:
 *
 * <pre>
 * {"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e", "status": "SETTLED",
 *  "reportId": "67a65fa611fe4db9bc67b8271c441990", "reportedAt": "2026-11-17T08:30:01Z"}
 * </pre>
 *
 * @param reportId   the pacs.002's MsgId
 * @param reportedAt when the other bank wrote the pacs.002
 */
public record TransferStatusChanged(UUID transferId, TransferStatus status, String reportId, Instant reportedAt) {

    public static final String TOPIC = "interbank.transfer-statuses";

    public TransferStatusChanged {
        if (transferId == null || status == null || reportId == null || reportedAt == null) {
            throw new IllegalArgumentException("transferId, status, reportId and reportedAt are all required");
        }
    }

    /** The pacs.002 names the transfer by its UETR, which Pacs008Mapper made the transfer id. */
    static TransferStatusChanged from(Pacs002 report) {
        return new TransferStatusChanged(UUID.fromString(report.originalUetr()), TransferStatus.of(report.status()),
                report.messageId(), report.createdAt());
    }
}
