package dev.tkachuk.integration.incoming;

/** A message we can't act on; it goes to its .dlq for a person to look at. */
class UnusableMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnusableMessageException(String message) {
        super(message);
    }
}
