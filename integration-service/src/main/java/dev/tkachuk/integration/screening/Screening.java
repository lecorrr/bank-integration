package dev.tkachuk.integration.screening;

import java.time.Instant;
import java.util.Objects;

/** What the screening service said about a payment, as stored with it. */
public record Screening(Outcome outcome, String screeningId, Instant screenedAt) {

    public enum Outcome { CLEAR, HIT }

    public Screening {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(screeningId, "screeningId");
        Objects.requireNonNull(screenedAt, "screenedAt");
    }

    public static Screening stored(String outcome, String screeningId, Instant screenedAt) {
        return outcome == null ? null : new Screening(Outcome.valueOf(outcome), screeningId, screenedAt);
    }

    public boolean cleared() {
        return outcome == Outcome.CLEAR;
    }
}
