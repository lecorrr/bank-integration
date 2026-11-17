package dev.tkachuk.integration.outgoing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The demo transfer, which tests change one field at a time. */
public final class TestTransfers {

    public static final UUID TRANSFER_ID = UUID.fromString("0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e");
    /** At our bank (code 399999) and at the partner bank (388888); both are valid IBANs. */
    public static final String OUR_CUSTOMER = "UA843999990000000000000000001";
    public static final String PARTNER_CUSTOMER = "UA643888880000000000000000777";

    private TestTransfers() {
    }

    public static TransferRequested transfer() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private UUID transferId = TRANSFER_ID;
        private String fromIban = OUR_CUSTOMER;
        private String fromName = "Artem Tkachuk";
        private String toIban = PARTNER_CUSTOMER;
        private String toName = "Olena Kovalenko";
        private BigDecimal amount = new BigDecimal("150.00");
        private String currency = "UAH";
        private String description = "Rent for November";
        private Instant requestedAt = Instant.parse("2026-11-17T08:30:00Z");

        public Builder transferId(UUID value) {
            transferId = value;
            return this;
        }

        public Builder fromIban(String value) {
            fromIban = value;
            return this;
        }

        public Builder toIban(String value) {
            toIban = value;
            return this;
        }

        public Builder toName(String value) {
            toName = value;
            return this;
        }

        public Builder amount(BigDecimal value) {
            amount = value;
            return this;
        }

        public Builder currency(String value) {
            currency = value;
            return this;
        }

        public Builder description(String value) {
            description = value;
            return this;
        }

        public Builder requestedAt(Instant value) {
            requestedAt = value;
            return this;
        }

        public TransferRequested build() {
            return new TransferRequested(transferId, fromIban, fromName, toIban, toName, amount, currency,
                    description, requestedAt);
        }
    }
}
