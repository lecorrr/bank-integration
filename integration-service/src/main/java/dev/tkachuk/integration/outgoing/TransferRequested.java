package dev.tkachuk.integration.outgoing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * bank-core asks for a transfer to a customer of another bank. By then bank-core has moved the money to its
 * settlement account and marked the transfer PENDING (step 3). Published to {@value #TOPIC}, keyed by fromIban,
 * so one account's transfers keep their order. As JSON:
 *
 * <pre>
 * {"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e",
 *  "fromIban": "UA843999990000000000000000001", "fromName": "Artem Tkachuk",
 *  "toIban": "UA643888880000000000000000777", "toName": "Olena Kovalenko",
 *  "amount": 150.00, "currency": "UAH", "description": "Rent for November",
 *  "requestedAt": "2026-11-17T08:30:00Z"}
 * </pre>
 *
 * The checks here are about the event itself; whether our bank can send it is Pacs008Mapper's question.
 *
 * @param transferId  bank-core's id for the transfer, a random UUID; it becomes the payment's UETR
 * @param description what the payee sees with the payment
 * @param requestedAt when bank-core accepted the transfer
 */
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

    /** UA, two check digits, the six-digit bank code, then the 19-character account number. */
    private static final Pattern UKRAINIAN_IBAN = Pattern.compile("UA[0-9]{8}[0-9A-Z]{19}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** ISO 20022's Max140Text, which the names and the description go into. */
    private static final int MAX_TEXT = 140;

    public TransferRequested {
        // Version 4 with the standard variant is the UUID form a UETR must have.
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
