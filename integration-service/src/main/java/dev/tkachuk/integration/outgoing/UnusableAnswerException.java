package dev.tkachuk.integration.outgoing;

/** A pacs.002 we can't act on; it goes to bank.pacs002.dlq. */
class UnusableAnswerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnusableAnswerException(String message) {
        super(message);
    }
}
