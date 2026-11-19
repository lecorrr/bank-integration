package dev.tkachuk.integration.outgoing;

/** Where a payment to another bank stands. SETTLED and REJECTED are final. */
enum PaymentState {

    NEW, // stored; screened or not, pacs.008 not confirmed yet
    SENT, // pacs.008 out, no answer yet
    PENDING, // the other bank answered PDNG
    SETTLED, // ACSC
    REJECTED; // RJCT, or refused by our bank

    boolean isFinal() {
        return this == SETTLED || this == REJECTED;
    }
}
