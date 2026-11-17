package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A valid incoming payment, as integration-service would send it. */
final class TestPayments {

    private TestPayments() {
    }

    static Pacs008 pacs008(String messageId) {
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
                "UA643888880000000000000000777",
                "PRTBUAUKXXX",
                "Rent for November");
    }
}
