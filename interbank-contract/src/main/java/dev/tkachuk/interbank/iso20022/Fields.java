package dev.tkachuk.interbank.iso20022;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Field rules from the ISO 20022 data types the two messages use. Errors name the field and the rule but
 * never the value: names and IBANs are personal data and end up in logs.
 */
final class Fields {

    /** IBAN2007Identifier. */
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[a-zA-Z0-9]{1,30}");
    /** BICFIDec2014Identifier. */
    private static final Pattern BIC = Pattern.compile("[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    /** UUIDv4Identifier: the UETR that follows one payment through every bank on its way. */
    private static final Pattern UETR =
            Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");
    /** ActiveOrHistoricCurrencyCode. */
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private Fields() {
    }

    /** Max35Text: message and transaction ids. */
    static void max35(String field, String value) {
        text(field, value, 35);
    }

    /** Max140Text: names and remittance information. */
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

    static void required(String field, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    /** ISODateTime with a zone, as both banks send it; a local time without one would be ambiguous. */
    static Instant instant(String field, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be a date-time with a zone or offset", e);
        }
    }

    static LocalDate date(String field, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be a date (yyyy-MM-dd)", e);
        }
    }

    static BigDecimal decimal(String field, String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a decimal number", e);
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
