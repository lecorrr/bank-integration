package dev.tkachuk.integration.reconciliation;

/** A statement that reads fine but can't be used; its file goes to error/ for a person to look at. */
class UnusableStatementException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnusableStatementException(String message) {
        super(message);
    }
}
