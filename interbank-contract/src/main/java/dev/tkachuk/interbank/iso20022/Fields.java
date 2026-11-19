package dev.tkachuk.interbank.iso20022;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Field checks shared by the message records. They mirror the XSD rules. */
final class Fields {

    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[a-zA-Z0-9]{1,30}");
    private static final Pattern BIC = Pattern.compile("[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final Pattern UETR =
            Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private Fields() {
    }

    static void max35(String field, String value) {
        text(field, value, 35);
    }

    static void max140(String field, String value) {
        text(field, value, 140);
    }

    static void iban(String field, String value) {
        matches(field, value, IBAN, "an IBAN");
    }

    static void bic(String field, String value) {
        matches(field, value, BIC, "a BIC");
    }

    static void uetr(String value) {
        matches("uetr", value, UETR, "a lower-case UUID version 4");
    }

    static void currency(String value) {
        matches("currency", value, CURRENCY, "a three-letter currency code");
    }

    static void positiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
    }

    static void reasonCode(String value) {
        if (value != null) {
            text("reasonCode", value, 4);
        }
    }

    static void required(String field, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void text(String field, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be 1 to " + maxLength + " characters");
        }
    }

    private static void matches(String field, String value, Pattern pattern, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be " + what);
        }
    }
}
