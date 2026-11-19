package dev.tkachuk.integration.outgoing;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When to ask about a payment that has no final answer (status-requests.* in application.yml). The wait doubles
 * after each request up to maxInterval, and the requests never stop.
 */
@ConfigurationProperties("status-requests")
record StatusRequestPolicy(Duration firstAfter, Duration maxInterval, int alarmAfter, Duration pollInterval) {

    private static final int MAX_DOUBLINGS = 20;

    StatusRequestPolicy {
        requirePositive("status-requests.first-after", firstAfter);
        requirePositive("status-requests.max-interval", maxInterval);
        requirePositive("status-requests.poll-interval", pollInterval);
        if (maxInterval.compareTo(firstAfter) < 0) {
            throw new IllegalArgumentException("status-requests.max-interval must not be shorter than first-after");
        }
        if (alarmAfter < 1) {
            throw new IllegalArgumentException("status-requests.alarm-after must be at least 1, got " + alarmAfter);
        }
    }

    Instant firstCheck(Instant sentAt) {
        return sentAt.plus(firstAfter);
    }

    Instant nextCheck(Instant askedAt, int requests) {
        return askedAt.plus(waitAfter(requests));
    }

    Duration waitAfter(int requests) {
        Duration wait = firstAfter.multipliedBy(1L << Math.clamp(requests, 0, MAX_DOUBLINGS));
        return wait.compareTo(maxInterval) > 0 ? maxInterval : wait;
    }

    boolean needsAttention(int requests) {
        return requests >= alarmAfter;
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration such as 30s, got " + value);
        }
    }
}
