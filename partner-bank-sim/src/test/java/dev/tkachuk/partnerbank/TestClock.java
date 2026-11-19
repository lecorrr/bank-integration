package dev.tkachuk.partnerbank;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand. */
final class TestClock extends Clock {

    private Instant now;

    TestClock(Instant now) {
        this.now = now;
    }

    void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("not needed by the tests");
    }

    @Override
    public Instant instant() {
        return now;
    }
}
