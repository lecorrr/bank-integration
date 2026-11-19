package dev.tkachuk.integration.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.incoming.TestPayments;
import dev.tkachuk.integration.screening.ScreeningProperties.CircuitBreakerSettings;
import dev.tkachuk.integration.screening.soap.Direction;
import dev.tkachuk.integration.screening.soap.Outcome;
import dev.tkachuk.integration.screening.soap.ScreenPaymentRequest;
import dev.tkachuk.integration.screening.soap.ScreenPaymentResponse;
import dev.tkachuk.interbank.iso20022.Pacs008;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SanctionsScreeningTest {

    private static final Instant NOW = Instant.parse("2026-11-19T09:00:00.700Z");
    private static final CircuitBreakerSettings SETTINGS =
            new CircuitBreakerSettings(50, 4, 2, Duration.ofMillis(200), 1);

    private final Pacs008 payment = TestPayments.payment();
    private final CircuitBreaker breaker = ScreeningConfiguration.circuitBreaker(SETTINGS);
    private final List<ScreenPaymentRequest> calls = new ArrayList<>();

    @Test
    void shouldScreenOutgoingPaymentAndTruncateTime() {
        Screening screening = screeningAnswering(request -> answer(request, Outcome.CLEAR)).screenOutgoing(payment);

        assertThat(screening).isEqualTo(new Screening(Screening.Outcome.CLEAR, "SCR-1",
                Instant.parse("2026-11-19T09:00:00Z")));
        assertThat(calls.getFirst().getDirection()).isEqualTo(Direction.OUTGOING);
    }

    @Test
    void shouldScreenIncomingPaymentAsIncoming() {
        Screening screening = screeningAnswering(request -> answer(request, Outcome.HIT)).screenIncoming(payment);

        assertThat(screening.cleared()).isFalse();
        assertThat(calls.getFirst().getDirection()).isEqualTo(Direction.INCOMING);
    }

    @Test
    void shouldOpenBreakerAfterFailures() {
        SanctionsScreening screening = screeningAnswering(request -> {
            throw new ScreeningUnavailableException("the service is down");
        });

        failsUnavailable(screening, "the service is down");
        failsUnavailable(screening, "the service is down");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        failsUnavailable(screening, "circuit breaker");

        assertThat(calls).as("calls that reached the service").hasSize(2);
    }

    @Test
    void shouldCountInvalidResponseAsFailure() {
        SanctionsScreening screening = screeningAnswering(request -> {
            ScreenPaymentResponse aboutAnother = answer(request, Outcome.CLEAR);
            aboutAnother.setPaymentReference("9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f");
            return aboutAnother;
        });

        failsUnavailable(screening, "is for payment");
        failsUnavailable(screening, "is for payment");

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void shouldNotCountRefusedRequests() {
        SanctionsScreening screening = screeningAnswering(request -> {
            throw new ScreeningRefusedException("refused");
        });

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> screening.screenOutgoing(payment)).isInstanceOf(ScreeningRefusedException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(calls).hasSize(5);
    }

    @Test
    void shouldCloseBreakerAfterSuccessfulTrialCall() throws InterruptedException {
        AtomicBoolean down = new AtomicBoolean(true);
        SanctionsScreening screening = screeningAnswering(request -> {
            if (down.get()) {
                throw new ScreeningUnavailableException("the service is down");
            }
            return answer(request, Outcome.CLEAR);
        });
        failsUnavailable(screening, "the service is down");
        failsUnavailable(screening, "the service is down");
        down.set(false);

        Thread.sleep(SETTINGS.waitInOpenState().toMillis() + 100);

        assertThat(screening.screenOutgoing(payment).cleared()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldReopenBreakerWhenTrialCallFails() throws InterruptedException {
        SanctionsScreening screening = screeningAnswering(request -> {
            throw new ScreeningUnavailableException("the service is down");
        });
        failsUnavailable(screening, "the service is down");
        failsUnavailable(screening, "the service is down");

        Thread.sleep(SETTINGS.waitInOpenState().toMillis() + 100);
        failsUnavailable(screening, "the service is down");

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(calls).as("the two failures and the trial").hasSize(3);
    }

    private SanctionsScreening screeningAnswering(Function<ScreenPaymentRequest, ScreenPaymentResponse> service) {
        ScreeningService counted = request -> {
            calls.add(request);
            return service.apply(request);
        };
        return new SanctionsScreening(counted, breaker, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void failsUnavailable(SanctionsScreening screening, String why) {
        assertThatThrownBy(() -> screening.screenOutgoing(payment))
                .isInstanceOf(ScreeningUnavailableException.class)
                .hasMessageContaining(why);
    }

    private static ScreenPaymentResponse answer(ScreenPaymentRequest request, Outcome outcome) {
        ScreenPaymentResponse response = new ScreenPaymentResponse();
        response.setPaymentReference(request.getPaymentReference());
        response.setScreeningId("SCR-1");
        response.setOutcome(outcome);
        return response;
    }
}
