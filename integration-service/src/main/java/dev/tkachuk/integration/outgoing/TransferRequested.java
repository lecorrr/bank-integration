package dev.tkachuk.integration.outgoing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** bank-core's event for a transfer to another bank (topic interbank.transfers). */
public record TransferRequested(
        UUID transferId,
        String fromIban,
        String fromName,
        String toIban,
        String toName,
        BigDecimal amount,
        String currency,
        String description,
        Instant requestedAt) {

    public static final String TOPIC = "interbank.transfers";

    private static final Pattern UKRAINIAN_IBAN = Pattern.compile("UA[0-9]{8}[0-9A-Z]{19}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int MAX_TEXT = 140;

    public TransferRequested {
        if (transferId == null || transferId.version() != 4 || transferId.variant() != 2) {
            throw new IllegalArgumentException("transferId must be a random UUID (version 4); it becomes the UETR");
        }
        requireUkrainianIban("fromIban", fromIban);
        requireText("fromName", fromName);
        requireUkrainianIban("toIban", toIban);
        requireText("toName", toName);
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be three capital letters, such as UAH");
        }
        requireText("description", description);
        if (requestedAt == null) {
            throw new IllegalArgumentException("requestedAt is required");
        }
    }

    private static void requireUkrainianIban(String field, String value) {
        if (value == null || !UKRAINIAN_IBAN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a Ukrainian IBAN: UA, 27 more characters, no spaces");
        }
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) {
            throw new IllegalArgumentException(field + " must be 1 to " + MAX_TEXT + " characters");
        }
    }
}
