package dev.tkachuk.interbank.iso20022;

import dev.tkachuk.interbank.iso20022.pacs008.AccountIdentification4Choice;
import dev.tkachuk.interbank.iso20022.pacs008.ActiveCurrencyAndAmount;
import dev.tkachuk.interbank.iso20022.pacs008.BranchAndFinancialInstitutionIdentification6;
import dev.tkachuk.interbank.iso20022.pacs008.CashAccount38;
import dev.tkachuk.interbank.iso20022.pacs008.ChargeBearerType1Code;
import dev.tkachuk.interbank.iso20022.pacs008.CreditTransferTransaction39;
import dev.tkachuk.interbank.iso20022.pacs008.Document;
import dev.tkachuk.interbank.iso20022.pacs008.FIToFICustomerCreditTransferV08;
import dev.tkachuk.interbank.iso20022.pacs008.FinancialInstitutionIdentification18;
import dev.tkachuk.interbank.iso20022.pacs008.GroupHeader93;
import dev.tkachuk.interbank.iso20022.pacs008.ObjectFactory;
import dev.tkachuk.interbank.iso20022.pacs008.PartyIdentification135;
import dev.tkachuk.interbank.iso20022.pacs008.PaymentIdentification7;
import dev.tkachuk.interbank.iso20022.pacs008.RemittanceInformation16;
import dev.tkachuk.interbank.iso20022.pacs008.SettlementInstruction7;
import dev.tkachuk.interbank.iso20022.pacs008.SettlementMethod1Code;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One customer credit transfer between two banks: an ISO 20022 pacs.008.001.08 message carrying a single
 * transaction (our scheme's rule; the message format allows several). The XML is written and read with the
 * classes generated from the official XSD, package {@code pacs008}, and checked against that XSD both ways.
 *
 * @param messageId             GrpHdr/MsgId, unique per sending bank
 * @param endToEndId            set by the payer and passed on unchanged to the payee
 * @param transactionId         set by the first instructing bank
 * @param uetr                  the unique end-to-end transaction reference, a UUID that every bank keeps
 * @param settlementDate        the business day the banks settle on
 * @param remittanceInformation the payment details the payee sees
 */
public record Pacs008(
        String messageId,
        Instant createdAt,
        String endToEndId,
        String transactionId,
        String uetr,
        BigDecimal amount,
        String currency,
        LocalDate settlementDate,
        String debtorName,
        String debtorIban,
        String debtorAgentBic,
        String creditorName,
        String creditorIban,
        String creditorAgentBic,
        String remittanceInformation) {

    public static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    public static final String MESSAGE_NAME = "pacs.008.001.08";

    private static final Iso20022Xml<Document> XML =
            new Iso20022Xml<>(MESSAGE_NAME, NAMESPACE, Document.class, new ObjectFactory()::createDocument);
    private static final String TRANSACTION = "/Document/FIToFICstmrCdtTrf/CdtTrfTxInf";

    public Pacs008 {
        Fields.max35("messageId", messageId);
        Fields.required("createdAt", createdAt);
        Fields.max35("endToEndId", endToEndId);
        Fields.max35("transactionId", transactionId);
        Fields.uetr(uetr);
        Fields.positiveAmount(amount);
        Fields.currency(currency);
        Fields.required("settlementDate", settlementDate);
        Fields.max140("debtorName", debtorName);
        Fields.iban("debtorIban", debtorIban);
        Fields.bic("debtorAgentBic", debtorAgentBic);
        Fields.max140("creditorName", creditorName);
        Fields.iban("creditorIban", creditorIban);
        Fields.bic("creditorAgentBic", creditorAgentBic);
        Fields.max140("remittanceInformation", remittanceInformation);
    }

    /** @throws InvalidMessageException when the payment breaks the schema, e.g. an amount with six decimals */
    public String toXml() {
        FIToFICustomerCreditTransferV08 transfer = new FIToFICustomerCreditTransferV08();
        transfer.setGrpHdr(groupHeader());
        transfer.getCdtTrfTxInf().add(transaction());
        Document document = new Document();
        document.setFIToFICstmrCdtTrf(transfer);
        return XML.write(document);
    }

    /**
     * @throws InvalidMessageException when the XML is not a pacs.008.001.08 that the schema accepts, or not a
     *                                 single-transaction one with every field this project needs
     */
    public static Pacs008 parse(String xml) {
        FIToFICustomerCreditTransferV08 transfer = XML.read(xml).getFIToFICstmrCdtTrf();
        GroupHeader93 header = transfer.getGrpHdr();
        if (!"1".equals(header.getNbOfTxs())) {
            throw new InvalidMessageException(
                    "A " + MESSAGE_NAME + " must carry exactly one transaction, not " + header.getNbOfTxs());
        }
        CreditTransferTransaction39 transaction = XML.single(transfer.getCdtTrfTxInf(), TRANSACTION);
        PaymentIdentification7 ids = transaction.getPmtId();
        ActiveCurrencyAndAmount settlementAmount = transaction.getIntrBkSttlmAmt();
        try {
            return new Pacs008(
                    header.getMsgId(),
                    XmlDates.toInstant("CreDtTm", header.getCreDtTm()),
                    ids.getEndToEndId(),
                    XML.required(ids.getTxId(), TRANSACTION + "/PmtId/TxId"),
                    XML.required(ids.getUETR(), TRANSACTION + "/PmtId/UETR"),
                    settlementAmount.getValue(),
                    settlementAmount.getCcy(),
                    XmlDates.toLocalDate("IntrBkSttlmDt",
                            XML.required(transaction.getIntrBkSttlmDt(), TRANSACTION + "/IntrBkSttlmDt")),
                    XML.required(transaction.getDbtr().getNm(), TRANSACTION + "/Dbtr/Nm"),
                    ibanOf(transaction.getDbtrAcct(), TRANSACTION + "/DbtrAcct"),
                    bicOf(transaction.getDbtrAgt(), TRANSACTION + "/DbtrAgt"),
                    XML.required(transaction.getCdtr().getNm(), TRANSACTION + "/Cdtr/Nm"),
                    ibanOf(transaction.getCdtrAcct(), TRANSACTION + "/CdtrAcct"),
                    bicOf(transaction.getCdtrAgt(), TRANSACTION + "/CdtrAgt"),
                    remittanceOf(transaction.getRmtInf()));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Invalid " + MESSAGE_NAME + ": " + e.getMessage(), e);
        }
    }

    /** One transaction, settled through a clearing system (CLRG). */
    private GroupHeader93 groupHeader() {
        SettlementInstruction7 settlement = new SettlementInstruction7();
        settlement.setSttlmMtd(SettlementMethod1Code.CLRG);
        GroupHeader93 header = new GroupHeader93();
        header.setMsgId(messageId);
        header.setCreDtTm(XmlDates.toXml(createdAt));
        header.setNbOfTxs("1");
        header.setSttlmInf(settlement);
        return header;
    }

    /** The charges follow the scheme's rules (SLEV). */
    private CreditTransferTransaction39 transaction() {
        PaymentIdentification7 ids = new PaymentIdentification7();
        ids.setEndToEndId(endToEndId);
        ids.setTxId(transactionId);
        ids.setUETR(uetr);
        ActiveCurrencyAndAmount settlementAmount = new ActiveCurrencyAndAmount();
        settlementAmount.setValue(amount);
        settlementAmount.setCcy(currency);
        RemittanceInformation16 remittance = new RemittanceInformation16();
        remittance.getUstrd().add(remittanceInformation);

        CreditTransferTransaction39 transaction = new CreditTransferTransaction39();
        transaction.setPmtId(ids);
        transaction.setIntrBkSttlmAmt(settlementAmount);
        transaction.setIntrBkSttlmDt(XmlDates.toXml(settlementDate));
        transaction.setChrgBr(ChargeBearerType1Code.SLEV);
        transaction.setDbtr(party(debtorName));
        transaction.setDbtrAcct(account(debtorIban));
        transaction.setDbtrAgt(agent(debtorAgentBic));
        transaction.setCdtrAgt(agent(creditorAgentBic));
        transaction.setCdtr(party(creditorName));
        transaction.setCdtrAcct(account(creditorIban));
        transaction.setRmtInf(remittance);
        return transaction;
    }

    private static PartyIdentification135 party(String name) {
        PartyIdentification135 party = new PartyIdentification135();
        party.setNm(name);
        return party;
    }

    private static CashAccount38 account(String iban) {
        AccountIdentification4Choice id = new AccountIdentification4Choice();
        id.setIBAN(iban);
        CashAccount38 account = new CashAccount38();
        account.setId(id);
        return account;
    }

    private static BranchAndFinancialInstitutionIdentification6 agent(String bic) {
        FinancialInstitutionIdentification18 institution = new FinancialInstitutionIdentification18();
        institution.setBICFI(bic);
        BranchAndFinancialInstitutionIdentification6 agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(institution);
        return agent;
    }

    /** The schema also allows an account without an IBAN (Othr); our scheme does not. */
    private static String ibanOf(CashAccount38 account, String path) {
        return XML.required(XML.required(account, path).getId().getIBAN(), path + "/Id/IBAN");
    }

    private static String bicOf(BranchAndFinancialInstitutionIdentification6 agent, String path) {
        return XML.required(agent.getFinInstnId().getBICFI(), path + "/FinInstnId/BICFI");
    }

    /** One line of unstructured remittance information, as the record holds. */
    private static String remittanceOf(RemittanceInformation16 remittance) {
        String path = TRANSACTION + "/RmtInf";
        return XML.single(XML.required(remittance, path).getUstrd(), path + "/Ustrd");
    }
}
