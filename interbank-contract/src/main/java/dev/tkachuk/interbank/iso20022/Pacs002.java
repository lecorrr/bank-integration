package dev.tkachuk.interbank.iso20022;

import dev.tkachuk.interbank.iso20022.pacs002.Document;
import dev.tkachuk.interbank.iso20022.pacs002.FIToFIPaymentStatusReportV10;
import dev.tkachuk.interbank.iso20022.pacs002.GroupHeader91;
import dev.tkachuk.interbank.iso20022.pacs002.ObjectFactory;
import dev.tkachuk.interbank.iso20022.pacs002.OriginalGroupHeader17;
import dev.tkachuk.interbank.iso20022.pacs002.PaymentTransaction110;
import dev.tkachuk.interbank.iso20022.pacs002.StatusReason6Choice;
import dev.tkachuk.interbank.iso20022.pacs002.StatusReasonInformation12;
import java.time.Instant;
import java.util.Objects;

/** pacs.002.001.10, a bank's answer about one pacs.008. */
public record Pacs002(
        String messageId,
        Instant createdAt,
        String originalMessageId,
        String originalEndToEndId,
        String originalTransactionId,
        String originalUetr,
        TransactionStatus status,
        String reasonCode) {

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
        Fields.reasonCode(reasonCode);
    }

    public static Pacs002 answering(Pacs008 payment, TransactionStatus status, String messageId, Instant createdAt) {
        return report(payment, status, null, messageId, createdAt);
    }

    public static Pacs002 rejecting(Pacs008 payment, String reasonCode, String messageId, Instant createdAt) {
        Fields.required("reasonCode", reasonCode);
        return report(payment, TransactionStatus.RJCT, reasonCode, messageId, createdAt);
    }

    /** RJCT AG09: the answer to a status request about a payment we never received. */
    public static Pacs002 notReceived(Pacs028 request, String messageId, Instant createdAt) {
        return rejecting(request, StatusReason.PAYMENT_NOT_RECEIVED, messageId, createdAt);
    }

    public static Pacs002 rejecting(Pacs028 request, String reasonCode, String messageId, Instant createdAt) {
        Fields.required("reasonCode", reasonCode);
        return new Pacs002(messageId, createdAt, request.originalMessageId(), request.originalEndToEndId(),
                request.originalTransactionId(), request.originalUetr(), TransactionStatus.RJCT, reasonCode);
    }

    /** The same answer in a new report, as for a status request. */
    public Pacs002 repeat(String newMessageId, Instant newCreatedAt) {
        return new Pacs002(newMessageId, newCreatedAt, originalMessageId, originalEndToEndId, originalTransactionId,
                originalUetr, status, reasonCode);
    }

    private static Pacs002 report(Pacs008 payment, TransactionStatus status, String reasonCode, String messageId,
            Instant createdAt) {
        return new Pacs002(messageId, createdAt, payment.messageId(), payment.endToEndId(),
                payment.transactionId(), payment.uetr(), status, reasonCode);
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
        if (reasonCode != null) {
            StatusReason6Choice reason = new StatusReason6Choice();
            reason.setCd(reasonCode);
            StatusReasonInformation12 reasonInformation = new StatusReasonInformation12();
            reasonInformation.setRsn(reason);
            transaction.getStsRsnInf().add(reasonInformation);
        }

        FIToFIPaymentStatusReportV10 report = new FIToFIPaymentStatusReportV10();
        report.setGrpHdr(header);
        report.getOrgnlGrpInfAndSts().add(original);
        report.getTxInfAndSts().add(transaction);
        Document document = new Document();
        document.setFIToFIPmtStsRpt(report);
        return XML.write(document);
    }

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
                    TransactionStatus.fromCode(XML.required(transaction.getTxSts(), TRANSACTION + "/TxSts")),
                    reasonCodeOf(transaction));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Invalid " + MESSAGE_NAME + ": " + e.getMessage(), e);
        }
    }

    // A reason given in the bank's own words (Rsn/Prtry) leaves the code empty rather than failing the report.
    private static String reasonCodeOf(PaymentTransaction110 transaction) {
        return transaction.getStsRsnInf().stream()
                .map(StatusReasonInformation12::getRsn)
                .filter(Objects::nonNull)
                .map(StatusReason6Choice::getCd)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
