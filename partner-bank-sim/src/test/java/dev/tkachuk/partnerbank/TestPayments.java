package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A valid pacs.008 from bank-core's bank to this bank. */
final class TestPayments {

    static final String CLOSED_ACCOUNT = "UA843888880000000000000000999";
    static final String SILENT_ACCOUNT = "UA443888880000000000000000555";
    static final String OPEN_ACCOUNT = "UA643888880000000000000000777";
    static final String BANK_CORE_CUSTOMER = "UA843999990000000000000000001";

    private TestPayments() {
    }

    static Pacs008 pacs008(String messageId) {
        return pacs008(messageId, OPEN_ACCOUNT);
    }

    static Pacs008 pacs008(String messageId, String creditorIban) {
        return new Pacs008(
                messageId,
                Instant.parse("2026-11-17T08:30:00Z"),
                "E2E-20261117-0001",
                "TX-20261117-0001",
                "3b241101-e2bb-4255-8caf-4136c566a962",
                new BigDecimal("150.00"),
                "UAH",
                LocalDate.parse("2026-11-17"),
                "Artem Tkachuk",
                "UA843999990000000000000000001",
                "BNKCUAUKXXX",
                "Olena Kovalenko",
                creditorIban,
                "PRTBUAUKXXX",
                "Rent for November");
    }

    static Pacs008 fromBankCore(String uetr, String amount) {
        return fromBankCore(uetr, amount, "UAH");
    }

    static Pacs008 fromBankCore(String uetr, String amount, String currency) {
        String messageId = uetr.replace("-", "");
        return new Pacs008(messageId, Instant.parse("2026-11-17T08:30:00Z"), PaymentSender.NOT_PROVIDED, messageId,
                uetr, new BigDecimal(amount), currency, LocalDate.parse("2026-11-17"), "Artem Tkachuk",
                BANK_CORE_CUSTOMER, "BNKCUAUKXXX", "Olena Kovalenko", OPEN_ACCOUNT, "PRTBUAUKXXX", "Rent for November");
    }

    static Pacs008 toBankCore(String uetr) {
        String messageId = uetr.replace("-", "");
        return new Pacs008(messageId, Instant.parse("2026-11-18T09:15:00Z"), PaymentSender.NOT_PROVIDED, messageId,
                uetr, new BigDecimal("250.00"), "UAH", LocalDate.parse("2026-11-18"), "Olena Kovalenko", OPEN_ACCOUNT,
                "PRTBUAUKXXX", "Artem Demo", BANK_CORE_CUSTOMER, "BNKCUAUKXXX", "For the concert tickets");
    }
}
