package dev.tkachuk.integration.outgoing;

import dev.tkachuk.interbank.iso20022.TransactionStatus;

/** What the other bank's pacs.002 means for bank-core's transfer, in bank-core's words rather than ISO codes. */
public enum TransferStatus {

    /** ACSC: the money is settled at the other bank; bank-core completes the transfer. */
    SETTLED,
    /** RJCT: the other bank refused the payment; bank-core gives the money back. */
    REJECTED,
    /** PDNG: the other bank has not decided yet; the transfer stays pending. */
    PENDING;

    /** A switch without a default: a new ISO status that is not mapped here does not compile. */
    static TransferStatus of(TransactionStatus status) {
        return switch (status) {
            case ACSC -> SETTLED;
            case RJCT -> REJECTED;
            case PDNG -> PENDING;
        };
    }
}
