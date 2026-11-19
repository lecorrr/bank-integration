package dev.tkachuk.interbank.iso20022;

/** An ISO 20022 message that cannot be read, or breaks its schema or our scheme's rules. */
public class InvalidMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
