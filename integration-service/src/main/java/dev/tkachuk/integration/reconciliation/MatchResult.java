package dev.tkachuk.integration.reconciliation;

/** What reconciliation found for a statement line or one of our payments. Everything but MATCHED is a break. */
enum MatchResult {

    MATCHED, // booked, and settled on our side for the same amount
    AMOUNT_MISMATCH, // booked and settled, but for another amount
    NOT_SETTLED, // booked, but on our side the payment still waits for its answer, or was rejected
    UNKNOWN, // booked, but we have no such payment in that direction
    DUPLICATE, // booked again: an earlier line already booked this payment
    MISSING; // settled on our side that day, but no statement books it

    static MatchResult of(StatementLine line, String statementCurrency) {
        if (line.bookedBefore()) {
            return DUPLICATE;
        }
        if (!line.known()) {
            return UNKNOWN;
        }
        if (!line.settled()) {
            return NOT_SETTLED;
        }
        boolean sameAmount = statementCurrency.equals(line.ourCurrency())
                && line.amount().compareTo(line.ourAmount()) == 0;
        return sameAmount ? MATCHED : AMOUNT_MISMATCH;
    }

    boolean isBreak() {
        return this != MATCHED;
    }
}
