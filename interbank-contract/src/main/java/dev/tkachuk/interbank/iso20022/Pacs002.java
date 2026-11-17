package dev.tkachuk.interbank.iso20022;

import java.time.Instant;
import java.util.Map;

/**
 * The receiving bank's answer to one pacs.008: an ISO 20022 pacs.002.001.10 payment status report. It
 * repeats the original payment's ids, which is how the sending bank matches the answer to its payment.
 */
public record Pacs002(
        String messageId,
        Instant createdAt,
        String originalMessageId,
        String originalEndToEndId,
        String originalTransactionId,
        String originalUetr,
        TransactionStatus status) {

    public static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10";
    public static final String MESSAGE_NAME = "pacs.002.001.10";

    private static final XmlTemplate TEMPLATE = XmlTemplate.load(MESSAGE_NAME + ".xml");
    private static final String REPORT = "FIToFIPmtStsRpt/";

    public Pacs002 {
        Fields.max35("messageId", messageId);
        Fields.required("createdAt", createdAt);
        Fields.max35("originalMessageId", originalMessageId);
        Fields.max35("originalEndToEndId", originalEndToEndId);
        Fields.max35("originalTransactionId", originalTransactionId);
        Fields.uetr(originalUetr);
        Fields.required("status", status);
    }

    /** The status report for one payment, carrying that payment's ids. */
    public static Pacs002 answering(Pacs008 payment, TransactionStatus status, String messageId, Instant createdAt) {
        return new Pacs002(messageId, createdAt, payment.messageId(), payment.endToEndId(),
                payment.transactionId(), payment.uetr(), status);
    }

    public String toXml() {
        return TEMPLATE.render(Map.of(
                "messageId", messageId,
                "createdAt", createdAt.toString(),
                "originalMessageId", originalMessageId,
                "originalEndToEndId", originalEndToEndId,
                "originalTransactionId", originalTransactionId,
                "originalUetr", originalUetr,
                "status", status.name()));
    }

    /** @throws InvalidMessageException when the XML is not a pacs.002.001.10 with every field we need */
    public static Pacs002 parse(String xml) {
        Iso20022Xml document = Iso20022Xml.parse(xml, NAMESPACE, "pacs.002");
        try {
            return new Pacs002(
                    document.required(REPORT + "GrpHdr/MsgId"),
                    Fields.instant("CreDtTm", document.required(REPORT + "GrpHdr/CreDtTm")),
                    document.required(REPORT + "OrgnlGrpInfAndSts/OrgnlMsgId"),
                    document.required(REPORT + "TxInfAndSts/OrgnlEndToEndId"),
                    document.required(REPORT + "TxInfAndSts/OrgnlTxId"),
                    document.required(REPORT + "TxInfAndSts/OrgnlUETR"),
                    TransactionStatus.fromCode(document.required(REPORT + "TxInfAndSts/TxSts")));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Invalid pacs.002: " + e.getMessage(), e);
        }
    }
}
