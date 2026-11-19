package dev.tkachuk.integration.incoming;

import dev.tkachuk.integration.InterbankProperties;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** A valid pacs.008 from the partner bank to one of our accounts; tests change one field at a time. */
public final class TestPayments {

    public static final InterbankProperties BANK =
            new InterbankProperties("399999", "BNKCUAUKXXX", Map.of("388888", "PRTBUAUKXXX"));
    public static final String UETR = "3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34";
    public static final String MESSAGE_ID = "PRTB20261118091500000001";
    public static final String PARTNER_CUSTOMER = "UA643888880000000000000000777";
    public static final String OUR_CUSTOMER = "UA843999990000000000000000001";
    public static final Instant SENT_AT = Instant.parse("2026-11-18T09:15:00Z");

    private TestPayments() {
    }

    public static Pacs008 payment() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder another() {
        UUID uetr = UUID.randomUUID();
        return builder().uetr(uetr.toString()).messageId(uetr.toString().replace("-", ""));
    }

    public static final class Builder {

        private String messageId = MESSAGE_ID;
        private String uetr = UETR;
        private BigDecimal amount = new BigDecimal("250.00");
        private String currency = "UAH";
        private String debtorIban = PARTNER_CUSTOMER;
        private String debtorAgentBic = "PRTBUAUKXXX";
        private String creditorIban = OUR_CUSTOMER;
        private String creditorAgentBic = "BNKCUAUKXXX";
        private String debtorName = "Olena Kovalenko";
        private String creditorName = "Artem Demo";
        private String remittanceInformation = "For the concert tickets";

        public Builder messageId(String value) {
            messageId = value;
            return this;
        }

        public Builder uetr(String value) {
            uetr = value;
            return this;
        }

        public Builder amount(String value) {
            amount = new BigDecimal(value);
            return this;
        }

        public Builder currency(String value) {
            currency = value;
            return this;
        }

        public Builder debtorIban(String value) {
            debtorIban = value;
            return this;
        }

        public Builder debtorAgentBic(String value) {
            debtorAgentBic = value;
            return this;
        }

        public Builder creditorIban(String value) {
            creditorIban = value;
            return this;
        }

        public Builder creditorAgentBic(String value) {
            creditorAgentBic = value;
            return this;
        }

        public Builder debtorName(String value) {
            debtorName = value;
            return this;
        }

        public Builder creditorName(String value) {
            creditorName = value;
            return this;
        }

        public Builder remittanceInformation(String value) {
            remittanceInformation = value;
            return this;
        }

        public Pacs008 build() {
            return new Pacs008(messageId, SENT_AT, "NOTPROVIDED", messageId, uetr, amount, currency,
                    LocalDate.of(2026, 11, 18), debtorName, debtorIban, debtorAgentBic, creditorName,
                    creditorIban, creditorAgentBic, remittanceInformation);
        }
    }
}
