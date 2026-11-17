package dev.tkachuk.interbank.iso20022;

/** A message that is not well-formed XML, not the expected ISO 20022 message, or misses a field we require. */
public class InvalidMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
