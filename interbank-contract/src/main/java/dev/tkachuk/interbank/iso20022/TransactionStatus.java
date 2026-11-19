package dev.tkachuk.interbank.iso20022;

import java.util.Arrays;

/**
 * The ISO 20022 transaction status codes (TxSts) this project uses, a subset of
 * ExternalPaymentTransactionStatus1Code.
 */
public enum TransactionStatus {

    /** AcceptedSettlementCompleted: the money reached the creditor's bank. */
    ACSC,
    /** Pending: the receiving bank has not decided yet. */
    PDNG,
    /** Rejected: the payment will not be executed. */
    RJCT;

    /** The status for a TxSts code; any code outside this subset is an invalid message for us. */
    public static TransactionStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.name().equals(code))
                .findFirst()
                .orElseThrow(() -> new InvalidMessageException("Unsupported transaction status \"" + code + "\""));
    }
}
