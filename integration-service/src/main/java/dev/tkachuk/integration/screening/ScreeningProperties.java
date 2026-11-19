package dev.tkachuk.integration.screening;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** sanctions-screening.* in application.yml, checked at startup. */
@ConfigurationProperties("sanctions-screening")
record ScreeningProperties(String url, Duration connectTimeout, Duration readTimeout,
        CircuitBreakerSettings circuitBreaker) {

    ScreeningProperties {
        requireHttpUrl(url);
        requirePositive("sanctions-screening.connect-timeout", connectTimeout);
        requirePositive("sanctions-screening.read-timeout", readTimeout);
        if (circuitBreaker == null) {
            throw new IllegalArgumentException("sanctions-screening.circuit-breaker is missing");
        }
    }

    record CircuitBreakerSettings(int failureRateThreshold, int slidingWindowSize, int minimumNumberOfCalls,
            Duration waitInOpenState, int permittedCallsInHalfOpenState) {

        private static final String PREFIX = "sanctions-screening.circuit-breaker.";

        CircuitBreakerSettings {
            if (failureRateThreshold < 1 || failureRateThreshold > 100) {
                throw new IllegalArgumentException(PREFIX + "failure-rate-threshold must be a percentage from 1 "
                        + "to 100, got " + failureRateThreshold);
            }
            if (slidingWindowSize < 1) {
                throw new IllegalArgumentException(PREFIX + "sliding-window-size must be at least 1, got "
                        + slidingWindowSize);
            }
            if (minimumNumberOfCalls < 1 || minimumNumberOfCalls > slidingWindowSize) {
                throw new IllegalArgumentException(PREFIX + "minimum-number-of-calls must be from 1 to "
                        + "sliding-window-size (" + slidingWindowSize + "), got " + minimumNumberOfCalls);
            }
            requirePositive(PREFIX + "wait-in-open-state", waitInOpenState);
            if (permittedCallsInHalfOpenState < 1) {
                throw new IllegalArgumentException(PREFIX + "permitted-calls-in-half-open-state must be at least 1, "
                        + "got " + permittedCallsInHalfOpenState);
            }
        }
    }

    private static void requireHttpUrl(String url) {
        URI uri;
        try {
            uri = url == null ? null : URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sanctions-screening.url is not a URL: " + url, e);
        }
        if (uri == null || uri.getHost() == null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("sanctions-screening.url must be an http or https URL such as "
                    + "http://localhost:8084/ws/screening, got " + url);
        }
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration such as 5s, got " + value);
        }
    }
}
