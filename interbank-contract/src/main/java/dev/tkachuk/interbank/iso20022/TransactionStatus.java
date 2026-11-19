package dev.tkachuk.interbank.iso20022;

import java.util.Arrays;

public enum TransactionStatus {

    ACSC, // accepted, settlement completed
    PDNG, // received, not decided yet
    RJCT; // rejected

    public static TransactionStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.name().equals(code))
                .findFirst()
                .orElseThrow(() -> new InvalidMessageException("Unsupported transaction status \"" + code + "\""));
    }
}
