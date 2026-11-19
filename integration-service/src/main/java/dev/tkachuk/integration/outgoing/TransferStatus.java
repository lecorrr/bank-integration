package dev.tkachuk.integration.outgoing;

/** What bank-core hears about a transfer. */
public enum TransferStatus {

    SETTLED,
    REJECTED,
    PENDING
}
