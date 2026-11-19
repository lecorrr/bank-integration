package dev.tkachuk.integration.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.screening.ScreeningProperties.CircuitBreakerSettings;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScreeningPropertiesTest {

    private static final String URL = "http://localhost:8084/ws/screening";
    private static final Duration TWO_SECONDS = Duration.ofSeconds(2);
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);
    private static final CircuitBreakerSettings BREAKER =
            new CircuitBreakerSettings(50, 10, 5, Duration.ofSeconds(30), 2);

    @Test
    void shouldLoadApplicationYmlSettings() {
        ScreeningProperties properties = new ScreeningProperties(URL, TWO_SECONDS, FIVE_SECONDS, BREAKER);

        assertThat(properties.url()).isEqualTo(URL);
        assertThat(properties.circuitBreaker().minimumNumberOfCalls()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "localhost:8084/ws/screening", "ftp://localhost/ws", "http:///ws/screening",
            "http://local host/ws"})
    void shouldRejectNonHttpUrl(String url) {
        assertThatThrownBy(() -> new ScreeningProperties(url, TWO_SECONDS, FIVE_SECONDS, BREAKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sanctions-screening.url");
    }

    @Test
    void shouldRequireUrl() {
        assertThatThrownBy(() -> new ScreeningProperties(null, TWO_SECONDS, FIVE_SECONDS, BREAKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sanctions-screening.url");
    }

    static Stream<Arguments> wrongTimeouts() {
        return Stream.of(
                Arguments.of("no connect timeout", null, FIVE_SECONDS, "connect-timeout"),
                Arguments.of("a zero read timeout", TWO_SECONDS, Duration.ZERO, "read-timeout"),
                Arguments.of("a negative connect timeout", Duration.ofSeconds(-1), FIVE_SECONDS, "connect-timeout"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongTimeouts")
    void shouldRejectNonPositiveTimeout(String setting, Duration connect, Duration read, String named) {
        assertThatThrownBy(() -> new ScreeningProperties(URL, connect, read, BREAKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sanctions-screening." + named);
    }

    @Test
    void shouldRequireCircuitBreakerSettings() {
        assertThatThrownBy(() -> new ScreeningProperties(URL, TWO_SECONDS, FIVE_SECONDS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sanctions-screening.circuit-breaker");
    }

    static Stream<Arguments> wrongBreakers() {
        Duration wait = Duration.ofSeconds(30);
        return Stream.of(
                Arguments.of("a threshold of 0", 0, 10, 5, wait, 2, "failure-rate-threshold"),
                Arguments.of("a threshold over 100", 101, 10, 5, wait, 2, "failure-rate-threshold"),
                Arguments.of("an empty window", 50, 0, 1, wait, 2, "sliding-window-size"),
                Arguments.of("no minimum", 50, 10, 0, wait, 2, "minimum-number-of-calls"),
                Arguments.of("a minimum past the window", 50, 10, 11, wait, 2, "minimum-number-of-calls"),
                Arguments.of("no wait", 50, 10, 5, Duration.ZERO, 2, "wait-in-open-state"),
                Arguments.of("no trial calls", 50, 10, 5, wait, 0, "permitted-calls-in-half-open-state"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongBreakers")
    void shouldRejectInvalidBreakerSetting(String setting, int threshold, int window, int minimum,
            Duration wait, int trials, String named) {
        assertThatThrownBy(() -> new CircuitBreakerSettings(threshold, window, minimum, wait, trials))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sanctions-screening.circuit-breaker." + named);
    }
}
