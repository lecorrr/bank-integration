package dev.tkachuk.interbank.iso20022;

/**
 * The status reason codes (ExternalStatusReason1Code, a pacs.002's StsRsnInf/Rsn/Cd) that the two banks use.
 * ISO 20022 publishes the full list, and a bank may answer with any code from it.
 */
public final class StatusReason {

    /** AC01 IncorrectAccountNumber: the payee's account does not exist at the receiving bank. */
    public static final String INCORRECT_ACCOUNT_NUMBER = "AC01";

    /** AC04 ClosedAccountNumber: the payee's account is closed at the receiving bank. */
    public static final String CLOSED_ACCOUNT = "AC04";

    /**
     * AG09 PaymentNotReceived: the bank asked about a payment never received it. It answers a status request
     * (pacs.028) and refuses nothing: the payment can be sent again.
     */
    public static final String PAYMENT_NOT_RECEIVED = "AG09";

    /** AGNT IncorrectAgent: the payment names a bank that is not the receiving bank, or one it does not know. */
    public static final String INCORRECT_AGENT = "AGNT";

    /** AM03 NotAllowedCurrency: the payment is in a currency the scheme does not settle. */
    public static final String NOT_ALLOWED_CURRENCY = "AM03";

    /**
     * AM05 Duplication: the payment repeats one the bank already has. It refuses the copy, not the payment, whose
     * own answer still stands.
     */
    public static final String DUPLICATION = "AM05";

    /** AM12 InvalidAmount: the amount cannot be paid as it is, such as hryvnia with more than two decimals. */
    public static final String INVALID_AMOUNT = "AM12";

    /** CURR IncorrectCurrency: the payee's account is kept in another currency than the payment's. */
    public static final String INCORRECT_CURRENCY = "CURR";

    /** MS03 NotSpecifiedReasonAgentGenerated: refused for a reason the bank does not say. */
    public static final String NOT_SPECIFIED = "MS03";

    private StatusReason() {
    }
}
