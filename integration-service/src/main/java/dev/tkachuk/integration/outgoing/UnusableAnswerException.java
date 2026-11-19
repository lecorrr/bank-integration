package dev.tkachuk.integration.outgoing;

/**
 * A valid pacs.002 that integration-service cannot act on: it answers no payment of ours, names one of ours with
 * another payment's UETR, or contradicts the payment's final answer. Like an unreadable one, it goes to the
 * dead-letter queue for a person. The message gives ids and statuses, never personal data.
 */
class UnusableAnswerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnusableAnswerException(String message) {
        super(message);
    }
}
