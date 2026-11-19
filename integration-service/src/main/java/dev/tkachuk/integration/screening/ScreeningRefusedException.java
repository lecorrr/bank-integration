package dev.tkachuk.integration.screening;

/** The screening service refused the request. Retrying won't help, so the message goes to a person. */
public class ScreeningRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScreeningRefusedException(String message) {
        super(message);
    }

    public ScreeningRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
