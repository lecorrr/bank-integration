package dev.tkachuk.integration.incoming;

import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Our event passing a payment from another bank to bank-core (topic interbank.incoming-transfers). */
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
