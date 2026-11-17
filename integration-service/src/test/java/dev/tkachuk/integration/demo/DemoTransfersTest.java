package dev.tkachuk.integration.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.integration.outgoing.TransferRequested;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DemoTransfersTest {

    @Test
    void everyTransferHasAFreshId() {
        DemoTransfers demoTransfers = new DemoTransfers(Clock.systemUTC());

        TransferRequested first = demoTransfers.next();
        TransferRequested second = demoTransfers.next();

        assertThat(second.transferId()).isNotEqualTo(first.transferId());
    }

    @Test
    void isRequestedNowToTheSecond() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-17T08:30:00.750Z"), ZoneOffset.UTC);

        assertThat(new DemoTransfers(clock).next().requestedAt()).isEqualTo(Instant.parse("2026-11-17T08:30:00Z"));
    }
}
