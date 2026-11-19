package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mistakes to put in a statement, for the demo: payments left out, booked for another amount, booked twice, and
 * bookings of payments nobody made. The account's books stay right, so the next statement opens where they say.
 */
record StatementFaults(List<String> leaveOut, Map<String, BigDecimal> changeAmounts, List<String> bookTwice,
        List<Unknown> addUnknown) {

    static final StatementFaults NONE = new StatementFaults(List.of(), Map.of(), List.of(), List.of());

    StatementFaults {
        leaveOut = lowerCase(leaveOut);
        bookTwice = lowerCase(bookTwice);
        changeAmounts = changeAmounts == null ? Map.of() : changeAmounts.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(entry -> entry.getKey().toLowerCase(Locale.ROOT),
                        Map.Entry::getValue));
        addUnknown = addUnknown == null ? List.of() : List.copyOf(addUnknown);
    }

    /** All the payments the faults name; each must be booked on the statement's day. */
    List<String> payments() {
        return Stream.of(leaveOut, List.copyOf(changeAmounts.keySet()), bookTwice)
                .flatMap(List::stream)
                .toList();
    }

    private static List<String> lowerCase(List<String> uetrs) {
        return uetrs == null ? List.of() : uetrs.stream().map(uetr -> uetr.toLowerCase(Locale.ROOT)).toList();
    }

    /** A booking for a payment nobody made. */
    record Unknown(CreditDebit direction, BigDecimal amount) {
    }
}
