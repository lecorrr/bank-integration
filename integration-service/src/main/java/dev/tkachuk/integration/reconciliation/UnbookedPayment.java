package dev.tkachuk.integration.reconciliation;

import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.util.UUID;

/** A payment settled on our side that no statement has booked yet. */
record UnbookedPayment(UUID uetr, CreditDebit direction, BigDecimal amount, String currency) {
}
