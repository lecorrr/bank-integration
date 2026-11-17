package dev.tkachuk.interbank.iso20022;

/**
 * A message that is not well-formed XML, not the expected ISO 20022 message, breaks its official schema, or
 * lacks something our scheme requires. It names the element and the rule but never quotes personal data such
 * as names and IBANs.
 */
public class InvalidMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
