package dev.tkachuk.integration.reconciliation;

import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.util.UUID;

/** A statement entry, next to what our tables say about the payment it books. */
record StatementLine(
        int line,
        String reference,
        CreditDebit direction,
        BigDecimal amount,
        UUID uetr,
        boolean bookedBefore, // an earlier line, in this statement or an earlier one, booked the same payment
        boolean known, // we have the payment: an outgoing one for DBIT, an incoming one for CRDT
        boolean settled, // and it is final on our side: SETTLED, or accepted with ACSC
        BigDecimal ourAmount, // empty when unknown, or refused by our bank before it had a pacs.008
        String ourCurrency) {
}
