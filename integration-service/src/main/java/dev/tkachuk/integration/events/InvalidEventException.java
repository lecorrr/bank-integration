package dev.tkachuk.integration.events;

/**
 * An event on Kafka that is not JSON, or breaks the rules of its type. It names the field and the rule but never
 * quotes personal data such as names and IBANs.
 */
public class InvalidEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
