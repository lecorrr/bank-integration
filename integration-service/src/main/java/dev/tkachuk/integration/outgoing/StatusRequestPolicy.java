package dev.tkachuk.integration.outgoing;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When to ask the other bank about a payment that has no final answer yet, from application.yml
 * (status-requests). The wait doubles after every status request, up to maxInterval, and never runs out:
 * silence is not an answer, so no payment is refused for want of one. From alarmAfter requests on, each one is
 * logged as an error, for a person to take the payment up with the other bank. Checked when the service starts.
 *
 * @param firstAfter   the first request is due this long after the pacs.008 went out
 * @param maxInterval  the longest wait between two requests
 * @param alarmAfter   from this many unanswered requests on, every request is logged as an error
 * @param pollInterval how often the service looks for payments due a request
 */
@ConfigurationProperties("status-requests")
record StatusRequestPolicy(Duration firstAfter, Duration maxInterval, int alarmAfter, Duration pollInterval) {

    /** 2^20 times any sensible first wait is far past maxInterval, and the doubling stops before it overflows. */
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

    /** When the first status request is due, for a pacs.008 that went out at sentAt. */
    Instant firstCheck(Instant sentAt) {
        return sentAt.plus(firstAfter);
    }

    /** When the next one is due, after the requests-th request went out at askedAt. */
    Instant nextCheck(Instant askedAt, int requests) {
        return askedAt.plus(waitAfter(requests));
    }

    /** firstAfter doubled once per request so far: 30 s, then 1, 2, 4, 8 minutes, and so on up to maxInterval. */
    Duration waitAfter(int requests) {
        Duration wait = firstAfter.multipliedBy(1L << Math.clamp(requests, 0, MAX_DOUBLINGS));
        return wait.compareTo(maxInterval) > 0 ? maxInterval : wait;
    }

    /** Whether this many requests without a final answer need a person to take it up with the other bank. */
    boolean needsAttention(int requests) {
        return requests >= alarmAfter;
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration such as 30s, got " + value);
        }
    }
}
