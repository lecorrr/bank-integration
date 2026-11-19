package dev.tkachuk.integration.reconciliation;

import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One day's statement checked against our payments: a result for each line, then one for each payment of ours
 * that no statement books. previous is the latest earlier statement, if there is one.
 */
record Reconciliation(StoredStatement statement, StoredStatement previous, List<Item> items) {

    Reconciliation {
        items = List.copyOf(items);
    }

    List<Item> breaks() {
        return items.stream().filter(item -> item.result().isBreak()).toList();
    }

    long lines() {
        return items.stream().filter(item -> item.reference() != null).count();
    }

    /** A gap means a statement in between is missing, or the partner bank's balances don't add up. */
    boolean continuesPrevious() {
        return previous == null || statement.openingBalance().compareTo(previous.closingBalance()) == 0;
    }

    /** A missing payment has no reference or booked amount; an unknown one has no amount of ours. */
    record Item(MatchResult result, CreditDebit direction, UUID uetr, String reference, BigDecimal bookedAmount,
            BigDecimal ourAmount) {

        static Item of(StatementLine line, MatchResult result) {
            return new Item(result, line.direction(), line.uetr(), line.reference(), line.amount(), line.ourAmount());
        }

        static Item missing(UnbookedPayment payment) {
            return new Item(MatchResult.MISSING, payment.direction(), payment.uetr(), null, null, payment.amount());
        }
    }
}
