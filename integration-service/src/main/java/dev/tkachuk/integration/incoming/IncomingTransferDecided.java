package dev.tkachuk.integration.incoming;

import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import java.util.UUID;

/** bank-core's decision on a payment from another bank (topic interbank.incoming-transfer-decisions). */
public record IncomingTransferDecided(UUID uetr, UUID transferId, Decision decision, String reason,
        Instant decidedAt) {

    public static final String TOPIC = "interbank.incoming-transfer-decisions";

    static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";

    public enum Decision {
        CREDITED,
        REJECTED
    }

    public IncomingTransferDecided {
        if (uetr == null || transferId == null || decision == null || decidedAt == null) {
            throw new IllegalArgumentException("uetr, transferId, decision and decidedAt are all required");
        }
        if (decision == Decision.CREDITED && reason != null) {
            throw new IllegalArgumentException("reason must be null for CREDITED");
        }
        if (decision == Decision.REJECTED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("reason is required for REJECTED");
        }
    }

    TransactionStatus status() {
        return decision == Decision.CREDITED ? TransactionStatus.ACSC : TransactionStatus.RJCT;
    }

    String reasonCode() {
        if (decision == Decision.CREDITED) {
            return null;
        }
        return switch (reason) {
            case UNKNOWN_ACCOUNT -> StatusReason.INCORRECT_ACCOUNT_NUMBER;
            case CURRENCY_MISMATCH -> StatusReason.INCORRECT_CURRENCY;
            default -> StatusReason.NOT_SPECIFIED;
        };
    }
}
