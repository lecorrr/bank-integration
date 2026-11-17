package dev.tkachuk.interbank.iso20022;

import dev.tkachuk.interbank.iso20022.pacs002.Document;
import dev.tkachuk.interbank.iso20022.pacs002.FIToFIPaymentStatusReportV10;
import dev.tkachuk.interbank.iso20022.pacs002.GroupHeader91;
import dev.tkachuk.interbank.iso20022.pacs002.ObjectFactory;
import dev.tkachuk.interbank.iso20022.pacs002.OriginalGroupHeader17;
import dev.tkachuk.interbank.iso20022.pacs002.PaymentTransaction110;
import java.time.Instant;

/**
 * The receiving bank's answer to one pacs.008: an ISO 20022 pacs.002.001.10 payment status report. It
 * repeats the original payment's ids, which is how the sending bank matches the answer to its payment. The XML
 * is written and read with the classes generated from the official XSD, package {@code pacs002}.
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

    private static final Iso20022Xml<Document> XML =
            new Iso20022Xml<>(MESSAGE_NAME, NAMESPACE, Document.class, new ObjectFactory()::createDocument);
    private static final String ORIGINAL_GROUP = "/Document/FIToFIPmtStsRpt/OrgnlGrpInfAndSts";
    private static final String TRANSACTION = "/Document/FIToFIPmtStsRpt/TxInfAndSts";

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
        GroupHeader91 header = new GroupHeader91();
        header.setMsgId(messageId);
        header.setCreDtTm(XmlDates.toXml(createdAt));

        OriginalGroupHeader17 original = new OriginalGroupHeader17();
        original.setOrgnlMsgId(originalMessageId);
        original.setOrgnlMsgNmId(Pacs008.MESSAGE_NAME);

        PaymentTransaction110 transaction = new PaymentTransaction110();
        transaction.setOrgnlEndToEndId(originalEndToEndId);
        transaction.setOrgnlTxId(originalTransactionId);
        transaction.setOrgnlUETR(originalUetr);
        transaction.setTxSts(status.name());

        FIToFIPaymentStatusReportV10 report = new FIToFIPaymentStatusReportV10();
        report.setGrpHdr(header);
        report.getOrgnlGrpInfAndSts().add(original);
        report.getTxInfAndSts().add(transaction);
        Document document = new Document();
        document.setFIToFIPmtStsRpt(report);
        return XML.write(document);
    }

    /**
     * @throws InvalidMessageException when the XML is not a pacs.002.001.10 that the schema accepts, or not a
     *                                 report on one transaction with every field we need
     */
    public static Pacs002 parse(String xml) {
        FIToFIPaymentStatusReportV10 report = XML.read(xml).getFIToFIPmtStsRpt();
        GroupHeader91 header = report.getGrpHdr();
        OriginalGroupHeader17 original = XML.single(report.getOrgnlGrpInfAndSts(), ORIGINAL_GROUP);
        PaymentTransaction110 transaction = XML.single(report.getTxInfAndSts(), TRANSACTION);
        try {
            return new Pacs002(
                    header.getMsgId(),
                    XmlDates.toInstant("CreDtTm", header.getCreDtTm()),
                    original.getOrgnlMsgId(),
                    XML.required(transaction.getOrgnlEndToEndId(), TRANSACTION + "/OrgnlEndToEndId"),
                    XML.required(transaction.getOrgnlTxId(), TRANSACTION + "/OrgnlTxId"),
                    XML.required(transaction.getOrgnlUETR(), TRANSACTION + "/OrgnlUETR"),
                    TransactionStatus.fromCode(XML.required(transaction.getTxSts(), TRANSACTION + "/TxSts")));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Invalid " + MESSAGE_NAME + ": " + e.getMessage(), e);
        }
    }
}
