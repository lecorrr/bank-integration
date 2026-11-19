package dev.tkachuk.interbank.iso20022;

import dev.tkachuk.interbank.iso20022.pacs028.BranchAndFinancialInstitutionIdentification6;
import dev.tkachuk.interbank.iso20022.pacs028.Document;
import dev.tkachuk.interbank.iso20022.pacs028.FIToFIPaymentStatusRequestV03;
import dev.tkachuk.interbank.iso20022.pacs028.FinancialInstitutionIdentification18;
import dev.tkachuk.interbank.iso20022.pacs028.GroupHeader91;
import dev.tkachuk.interbank.iso20022.pacs028.ObjectFactory;
import dev.tkachuk.interbank.iso20022.pacs028.OriginalGroupInformation29;
import dev.tkachuk.interbank.iso20022.pacs028.PaymentTransaction113;
import java.time.Instant;

/** pacs.028.001.03, a status request about one pacs.008. */
public record Pacs028(
        String messageId,
        Instant createdAt,
        String instructingAgentBic,
        String instructedAgentBic,
        String originalMessageId,
        Instant originalCreatedAt,
        String originalEndToEndId,
        String originalTransactionId,
        String originalUetr) {

    public static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.028.001.03";
    public static final String MESSAGE_NAME = "pacs.028.001.03";

    private static final Iso20022Xml<Document> XML =
            new Iso20022Xml<>(MESSAGE_NAME, NAMESPACE, Document.class, new ObjectFactory()::createDocument);
    private static final String HEADER = "/Document/FIToFIPmtStsReq/GrpHdr";
    private static final String TRANSACTION = "/Document/FIToFIPmtStsReq/TxInf";

    public Pacs028 {
        Fields.max35("messageId", messageId);
        Fields.required("createdAt", createdAt);
        Fields.bic("instructingAgentBic", instructingAgentBic);
        Fields.bic("instructedAgentBic", instructedAgentBic);
        Fields.max35("originalMessageId", originalMessageId);
        Fields.required("originalCreatedAt", originalCreatedAt);
        Fields.max35("originalEndToEndId", originalEndToEndId);
        Fields.max35("originalTransactionId", originalTransactionId);
        Fields.uetr(originalUetr);
    }

    public static Pacs028 about(Pacs008 payment, String messageId, Instant createdAt) {
        return new Pacs028(messageId, createdAt, payment.debtorAgentBic(), payment.creditorAgentBic(),
                payment.messageId(), payment.createdAt(), payment.endToEndId(), payment.transactionId(),
                payment.uetr());
    }

    public String toXml() {
        GroupHeader91 header = new GroupHeader91();
        header.setMsgId(messageId);
        header.setCreDtTm(XmlDates.toXml(createdAt));
        header.setInstgAgt(agent(instructingAgentBic));
        header.setInstdAgt(agent(instructedAgentBic));

        OriginalGroupInformation29 original = new OriginalGroupInformation29();
        original.setOrgnlMsgId(originalMessageId);
        original.setOrgnlMsgNmId(Pacs008.MESSAGE_NAME);
        original.setOrgnlCreDtTm(XmlDates.toXml(originalCreatedAt));

        PaymentTransaction113 transaction = new PaymentTransaction113();
        transaction.setOrgnlGrpInf(original);
        transaction.setOrgnlEndToEndId(originalEndToEndId);
        transaction.setOrgnlTxId(originalTransactionId);
        transaction.setOrgnlUETR(originalUetr);

        FIToFIPaymentStatusRequestV03 request = new FIToFIPaymentStatusRequestV03();
        request.setGrpHdr(header);
        request.getTxInf().add(transaction);
        Document document = new Document();
        document.setFIToFIPmtStsReq(request);
        return XML.write(document);
    }

    public static Pacs028 parse(String xml) {
        FIToFIPaymentStatusRequestV03 request = XML.read(xml).getFIToFIPmtStsReq();
        GroupHeader91 header = request.getGrpHdr();
        PaymentTransaction113 transaction = XML.single(request.getTxInf(), TRANSACTION);
        OriginalGroupInformation29 original = XML.required(transaction.getOrgnlGrpInf(), TRANSACTION + "/OrgnlGrpInf");
        if (!Pacs008.MESSAGE_NAME.equals(original.getOrgnlMsgNmId())) {
            throw new InvalidMessageException("A " + MESSAGE_NAME + " must ask about a " + Pacs008.MESSAGE_NAME
                    + ", not a " + original.getOrgnlMsgNmId());
        }
        try {
            return new Pacs028(
                    header.getMsgId(),
                    XmlDates.toInstant("CreDtTm", header.getCreDtTm()),
                    bicOf(header.getInstgAgt(), HEADER + "/InstgAgt"),
                    bicOf(header.getInstdAgt(), HEADER + "/InstdAgt"),
                    original.getOrgnlMsgId(),
                    XmlDates.toInstant("OrgnlCreDtTm",
                            XML.required(original.getOrgnlCreDtTm(), TRANSACTION + "/OrgnlGrpInf/OrgnlCreDtTm")),
                    XML.required(transaction.getOrgnlEndToEndId(), TRANSACTION + "/OrgnlEndToEndId"),
                    XML.required(transaction.getOrgnlTxId(), TRANSACTION + "/OrgnlTxId"),
                    XML.required(transaction.getOrgnlUETR(), TRANSACTION + "/OrgnlUETR"));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Invalid " + MESSAGE_NAME + ": " + e.getMessage(), e);
        }
    }

    private static BranchAndFinancialInstitutionIdentification6 agent(String bic) {
        FinancialInstitutionIdentification18 institution = new FinancialInstitutionIdentification18();
        institution.setBICFI(bic);
        BranchAndFinancialInstitutionIdentification6 agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(institution);
        return agent;
    }

    private static String bicOf(BranchAndFinancialInstitutionIdentification6 agent, String path) {
        return XML.required(XML.required(agent, path).getFinInstnId().getBICFI(), path + "/FinInstnId/BICFI");
    }
}
