package dev.tkachuk.integration.outgoing;

import dev.tkachuk.interbank.iso20022.Pacs002;
import java.time.Instant;
import java.util.UUID;

/** Our event telling bank-core how a transfer ended (topic interbank.transfer-statuses). */
public record TransferStatusChanged(UUID transferId, TransferStatus status, String reportId, Instant reportedAt) {

    public static final String TOPIC = "interbank.transfer-statuses";

    public TransferStatusChanged {
        if (transferId == null || status == null || reportId == null || reportedAt == null) {
            throw new IllegalArgumentException("transferId, status, reportId and reportedAt are all required");
        }
    }

    static TransferStatusChanged reported(UUID transferId, TransferStatus status, Pacs002 report) {
        return new TransferStatusChanged(transferId, status, report.messageId(), report.createdAt());
    }
}
