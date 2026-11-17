package dev.tkachuk.interbank.iso20022;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Valid messages for tests: bank-core's demo account A pays a customer of the partner bank. */
final class TestMessages {

    static final Instant CREATED_AT = Instant.parse("2026-11-17T08:30:00Z");

    private TestMessages() {
    }

    static Pacs008 pacs008() {
        return pacs008("Olena Kovalenko");
    }

    static Pacs008 pacs008(String creditorName) {
        return new Pacs008(
                "5f0c1e3a9b2d4e6f8a1b3c5d7e9f0a1b",
                CREATED_AT,
                "E2E-20261117-0001",
                "TX-20261117-0001",
                "3b241101-e2bb-4255-8caf-4136c566a962",
                new BigDecimal("150.00"),
                "UAH",
                LocalDate.parse("2026-11-17"),
                "Artem Tkachuk",
                "UA843999990000000000000000001",
                "BNKCUAUKXXX",
                creditorName,
                "UA643888880000000000000000777",
                "PRTBUAUKXXX",
                "Rent for November");
    }
}
