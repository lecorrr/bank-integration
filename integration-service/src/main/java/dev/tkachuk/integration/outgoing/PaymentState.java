package dev.tkachuk.integration.outgoing;

/**
 * Where a payment to another bank stands. SETTLED and REJECTED are final: only the other bank's answer leads to
 * them, and no later answer changes them.
 */
enum PaymentState {

    /** Stored; RabbitMQ has not yet confirmed the pacs.008. */
    NEW,
    /** The pacs.008 is out and has no answer yet. */
    SENT,
    /** The other bank answered PDNG: it has the payment and has not decided. */
    PENDING,
    /** ACSC: the money is at the other bank. */
    SETTLED,
    /** RJCT: the other bank refused the payment. */
    REJECTED;

    boolean isFinal() {
        return this == SETTLED || this == REJECTED;
    }
}
