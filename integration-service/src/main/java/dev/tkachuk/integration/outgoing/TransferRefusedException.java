package dev.tkachuk.integration.outgoing;

import java.util.UUID;

/**
 * A transfer our bank cannot send through SEP, such as one to a bank outside the directory. A definite answer,
 * not a passing failure, so trying again would not help; step 4 reports it to bank-core as REJECTED.
 */
class TransferRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    TransferRefusedException(UUID transferId, String reason) {
        super("Transfer " + transferId + " refused: " + reason);
    }
}
