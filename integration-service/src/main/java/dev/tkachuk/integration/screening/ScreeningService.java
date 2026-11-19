package dev.tkachuk.integration.screening;

import dev.tkachuk.integration.screening.soap.ScreenPaymentRequest;
import dev.tkachuk.integration.screening.soap.ScreenPaymentResponse;

/** One call to the screening service; lets the circuit breaker be tested without a server. */
@FunctionalInterface
interface ScreeningService {

    ScreenPaymentResponse call(ScreenPaymentRequest request);
}
