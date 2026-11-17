package dev.tkachuk.integration.demo;

import dev.tkachuk.integration.outgoing.TransferRequested;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The demo's one fixed transfer, with a fresh id each time: 150.00 UAH from bank-core's demo account A to a
 * customer of the partner bank, in the event bank-core itself publishes from step 3.
 */
@Component
class DemoTransfers {

    private final Clock clock;

    DemoTransfers(Clock clock) {
        this.clock = clock;
    }

    TransferRequested next() {
        return new TransferRequested(
                UUID.randomUUID(),
                "UA843999990000000000000000001",
                "Artem Tkachuk",
                "UA643888880000000000000000777",
                "Olena Kovalenko",
                new BigDecimal("150.00"),
                "UAH",
                "Rent for November",
                clock.instant().truncatedTo(ChronoUnit.SECONDS));
    }
}
