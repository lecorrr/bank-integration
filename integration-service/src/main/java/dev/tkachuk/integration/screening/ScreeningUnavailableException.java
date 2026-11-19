package dev.tkachuk.integration.screening;

/** No screening answer right now (service down, slow or invalid, or circuit open); the route retries. */
public class ScreeningUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScreeningUnavailableException(String message) {
        super(message);
    }

    public ScreeningUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
