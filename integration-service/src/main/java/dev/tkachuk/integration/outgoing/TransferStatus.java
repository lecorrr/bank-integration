package dev.tkachuk.integration.outgoing;

/** What became of a transfer at the other bank, in bank-core's words rather than ISO codes. */
public enum TransferStatus {

    /** The money is settled at the other bank (ACSC); bank-core completes the transfer. */
    SETTLED,
    /** The other bank refused the payment (RJCT), or our bank refused to send it; bank-core gives the money back. */
    REJECTED,
    /** The other bank has the payment and has not decided (PDNG); the transfer stays pending. */
    PENDING
}
