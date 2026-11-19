package dev.tkachuk.integration.incoming;

/**
 * A message that is valid, but that flow B cannot act on, such as a pacs.008 reusing the MsgId of another payment,
 * or a decision from bank-core about a payment we never received. It goes to a dead-letter queue or topic, as it
 * came, for a person to look at. Like every reason given there, it names ids but never personal data.
 */
class UnusableMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnusableMessageException(String message) {
        super(message);
    }
}
