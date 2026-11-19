package dev.tkachuk.interbank.iso20022;

/** ISO 20022 status reason codes (ExternalStatusReason1Code) used in pacs.002. */
public final class StatusReason {

    public static final String INCORRECT_ACCOUNT_NUMBER = "AC01";
    public static final String CLOSED_ACCOUNT = "AC04";
    /** Answer to a status request about a payment the bank never received; the payment can be sent again. */
    public static final String PAYMENT_NOT_RECEIVED = "AG09";
    public static final String INCORRECT_AGENT = "AGNT";
    public static final String NOT_ALLOWED_CURRENCY = "AM03";
    /** Refuses a duplicate message, not the payment: the payment's own answer still stands. */
    public static final String DUPLICATION = "AM05";
    public static final String INVALID_AMOUNT = "AM12";
    public static final String INCORRECT_CURRENCY = "CURR";
    public static final String NOT_SPECIFIED = "MS03";
    /** Regulatory reason, such as a sanctions hit. */
    public static final String REGULATORY_REASON = "RR04";

    private StatusReason() {
    }
}
