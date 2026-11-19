package dev.tkachuk.integration.outgoing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class StatusRequestPolicyTest {

    private static final Instant SENT_AT = Instant.parse("2026-11-17T08:30:00Z");
    private static final Duration HALF_A_MINUTE = Duration.ofSeconds(30);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    private final StatusRequestPolicy policy = new StatusRequestPolicy(HALF_A_MINUTE, TEN_MINUTES, 5, FIVE_SECONDS);

    @Test
    void shouldAskFirstAfterInitialWait() {
        assertThat(policy.firstCheck(SENT_AT)).isEqualTo(SENT_AT.plus(HALF_A_MINUTE));
    }

    @ParameterizedTest(name = "after request {0}: {1}")
    @CsvSource({"0, PT30S", "1, PT1M", "2, PT2M", "3, PT4M", "4, PT8M", "5, PT10M", "6, PT10M", "2147483647, PT10M"})
    void shouldDoubleWaitUpToMaxInterval(int requests, Duration wait) {
        assertThat(policy.waitAfter(requests)).isEqualTo(wait);
    }

    @Test
    void shouldScheduleNextRequestFromRequestTime() {
        Instant askedAt = SENT_AT.plus(Duration.ofMinutes(5));

        assertThat(policy.nextCheck(askedAt, 2)).isEqualTo(askedAt.plus(Duration.ofMinutes(2)));
    }

    @Test
    void shouldAlarmFromThreshold() {
        assertThat(policy.needsAttention(4)).isFalse();
        assertThat(policy.needsAttention(5)).isTrue();
        assertThat(policy.needsAttention(50)).isTrue();
    }

    static Stream<Arguments> unworkablePolicies() {
        return Stream.of(
                Arguments.of(Duration.ZERO, TEN_MINUTES, 5, FIVE_SECONDS, "first-after"),
                Arguments.of(HALF_A_MINUTE, Duration.ofSeconds(-1), 5, FIVE_SECONDS, "max-interval"),
                Arguments.of(HALF_A_MINUTE, TEN_MINUTES, 5, null, "poll-interval"),
                Arguments.of(TEN_MINUTES, HALF_A_MINUTE, 5, FIVE_SECONDS, "max-interval must not be shorter"),
                Arguments.of(HALF_A_MINUTE, TEN_MINUTES, 0, FIVE_SECONDS, "alarm-after"));
    }

    @ParameterizedTest(name = "{4}")
    @MethodSource("unworkablePolicies")
    void shouldRejectInvalidPolicy(Duration firstAfter, Duration maxInterval, int alarmAfter,
            Duration pollInterval, String complaint) {
        assertThatThrownBy(() -> new StatusRequestPolicy(firstAfter, maxInterval, alarmAfter, pollInterval))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(complaint);
    }
}
