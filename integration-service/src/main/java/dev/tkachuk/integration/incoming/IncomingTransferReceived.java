package dev.tkachuk.integration.incoming;

import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment from another bank to a customer of bank-core, for bank-core to credit or refuse; its answer is an
 * IncomingTransferDecided. Published to {@value #TOPIC}, keyed by toIban, so the payments to one account keep
 * their order. As JSON:
 *
 * <pre>
 * {"uetr": "3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34",
 *  "fromIban": "UA643888880000000000000000777", "fromName": "Olena Kovalenko",
 *  "toIban": "UA843999990000000000000000001", "toName": "Artem Demo",
 *  "amount": 250.00, "currency": "UAH", "description": "For the concert tickets",
 *  "receivedAt": "2026-11-18T09:15:00Z"}
 * </pre>
 *
 * The same payment can come more than once, after a crash or when the other bank sends it again; bank-core credits
 * it once, by its UETR, and answers a copy with the decision it made the first time.
 *
 * @param uetr        the payment's unique end-to-end reference, which the other bank gave it
 * @param description what the payer wrote for the payee
 * @param receivedAt  when our bank received the pacs.008
 */
public record IncomingTransferReceived(
        UUID uetr,
        String fromIban,
        String fromName,
        String toIban,
        String toName,
        BigDecimal amount,
        String currency,
        String description,
        Instant receivedAt) {

    public static final String TOPIC = "interbank.incoming-transfers";

    public IncomingTransferReceived {
        if (uetr == null || fromIban == null || fromName == null || toIban == null || toName == null
                || amount == null || currency == null || description == null || receivedAt == null) {
            throw new IllegalArgumentException("Every field of an IncomingTransferReceived is required");
        }
    }

    /** The payment as bank-core sees it; IncomingChecks has passed it, so the amount has two decimals at most. */
    static IncomingTransferReceived of(Pacs008 payment, Instant receivedAt) {
        return new IncomingTransferReceived(
                UUID.fromString(payment.uetr()),
                payment.debtorIban(),
                payment.debtorName(),
                payment.creditorIban(),
                payment.creditorName(),
                payment.amount().setScale(2),
                payment.currency(),
                payment.remittanceInformation(),
                receivedAt);
    }
}
