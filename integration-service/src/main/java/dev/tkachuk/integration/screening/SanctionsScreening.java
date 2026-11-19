package dev.tkachuk.integration.screening;

import dev.tkachuk.integration.screening.soap.Direction;
import dev.tkachuk.integration.screening.soap.ScreenPaymentRequest;
import dev.tkachuk.integration.screening.soap.ScreenPaymentResponse;
import dev.tkachuk.interbank.iso20022.Pacs008;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Screens payments against the sanctions lists through a circuit breaker. Only service failures
 * (ScreeningUnavailableException) count towards opening it; refused requests don't.
 */
@Component
public class SanctionsScreening {

    private static final Logger log = LoggerFactory.getLogger(SanctionsScreening.class);

    private final ScreeningService service;
    private final CircuitBreaker breaker;
    private final Clock clock;

    SanctionsScreening(ScreeningService service, CircuitBreaker sanctionsScreeningBreaker, Clock clock) {
        this.service = service;
        this.breaker = sanctionsScreeningBreaker;
        this.clock = clock;
    }

    public Screening screenOutgoing(Pacs008 payment) {
        return screen(payment, Direction.OUTGOING);
    }

    public Screening screenIncoming(Pacs008 payment) {
        return screen(payment, Direction.INCOMING);
    }

    private Screening screen(Pacs008 payment, Direction direction) {
        ScreenPaymentRequest request = ScreeningMessages.requestFor(payment, direction);
        try {
            return breaker.executeSupplier(() -> answerTo(request));
        } catch (CallNotPermittedException e) {
            throw new ScreeningUnavailableException("Payment " + payment.uetr()
                    + " not screened: circuit breaker is open", e);
        }
    }

    private Screening answerTo(ScreenPaymentRequest request) {
        ScreenPaymentResponse response = service.call(request);
        Screening screening = ScreeningMessages.screeningOf(request, response,
                clock.instant().truncatedTo(ChronoUnit.SECONDS));
        if (!screening.cleared()) {
            log.warn("Payment {}: sanctions hit (screening {}), {}", request.getPaymentReference(),
                    screening.screeningId(), ScreeningMessages.matchesOf(response));
        }
        return screening;
    }
}
