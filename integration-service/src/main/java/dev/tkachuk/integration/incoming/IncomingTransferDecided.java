package dev.tkachuk.integration.incoming;

import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * bank-core's decision on an IncomingTransferReceived: the money credited to the payee, or the payment refused.
 * bank-core publishes it to {@value #TOPIC}, keyed by the UETR. As JSON:
 *
 * <pre>
 * {"uetr": "3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34", "transferId": "9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f",
 *  "decision": "REJECTED", "reason": "UNKNOWN_ACCOUNT", "decidedAt": "2026-11-18T09:15:01Z"}
 * </pre>
 *
 * @param transferId bank-core's id for the transfer it made of the payment, credited or refused
 * @param reason     why bank-core refused the payment, in its own words; null for a payment credited. A reason this
 *                   service does not know yet is still a refusal, answered with MS03 (reason not specified)
 * @param decidedAt  when bank-core decided
 */
public record IncomingTransferDecided(UUID uetr, UUID transferId, Decision decision, String reason,
        Instant decidedAt) {

    public static final String TOPIC = "interbank.incoming-transfer-decisions";

    /** bank-core's words for a payee account it does not keep. */
    static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    /** bank-core's words for a payee account kept in another currency than the payment's. */
    static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";

    public enum Decision {
        /** The payee has the money. */
        CREDITED,
        /** The payee does not get the money; the other bank gives it back to the payer. */
        REJECTED
    }

    public IncomingTransferDecided {
        if (uetr == null || transferId == null || decision == null || decidedAt == null) {
            throw new IllegalArgumentException("uetr, transferId, decision and decidedAt are all required");
        }
        if (decision == Decision.CREDITED && reason != null) {
            throw new IllegalArgumentException("A payment credited has no reason");
        }
        if (decision == Decision.REJECTED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A payment refused needs a reason");
        }
    }

    /** The pacs.002 status that tells the other bank: ACSC for a payment credited, RJCT for one refused. */
    TransactionStatus status() {
        return decision == Decision.CREDITED ? TransactionStatus.ACSC : TransactionStatus.RJCT;
    }

    /** The ISO reason code for bank-core's reason; null for a payment credited. */
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
