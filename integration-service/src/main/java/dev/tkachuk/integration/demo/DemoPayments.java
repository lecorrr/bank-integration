package dev.tkachuk.integration.demo;

import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The walking skeleton's one fixed payment, with fresh ids each time: 150.00 UAH from bank-core's demo
 * account A to a customer of the partner bank. Step 2 builds pacs.008 messages from bank-core's transfers.
 */
@Component
class DemoPayments {

    /** The business day follows Kyiv time, not UTC: a payment at 01:00 Kyiv time settles on the Kyiv date. */
    private static final ZoneId BANK_ZONE = ZoneId.of("Europe/Kyiv");

    private final Clock clock;

    DemoPayments(Clock clock) {
        this.clock = clock;
    }

    Pacs008 next() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        return new Pacs008(
                newId(),
                now,
                newId(),
                newId(),
                UUID.randomUUID().toString(),
                new BigDecimal("150.00"),
                "UAH",
                LocalDate.ofInstant(now, BANK_ZONE),
                "Artem Tkachuk",
                "UA843999990000000000000000001",
                "BNKCUAUKXXX",
                "Olena Kovalenko",
                "UA643888880000000000000000777",
                "PRTBUAUKXXX",
                "Rent for November");
    }

    /** 32 hex characters: unique, and within ISO 20022's 35-character limit for ids. */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
