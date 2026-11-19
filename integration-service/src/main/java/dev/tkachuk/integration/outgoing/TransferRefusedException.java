package dev.tkachuk.integration.outgoing;

import java.util.UUID;

/** A transfer our bank can't send. It is reported to bank-core as REJECTED, not retried. */
class TransferRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID transferId;

    TransferRefusedException(UUID transferId, String reason) {
        super("Transfer " + transferId + " refused: " + reason);
        this.transferId = transferId;
    }

    UUID transferId() {
        return transferId;
    }
}
