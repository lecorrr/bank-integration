package dev.tkachuk.interbank.iso20022;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * One customer credit transfer between two banks: an ISO 20022 pacs.008.001.08 message carrying a single
 * transaction (our scheme's rule; the message format allows several).
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

    private static final XmlTemplate TEMPLATE = XmlTemplate.load(MESSAGE_NAME + ".xml");
    private static final String HEADER = "FIToFICstmrCdtTrf/GrpHdr/";
    private static final String TX = "FIToFICstmrCdtTrf/CdtTrfTxInf/";

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

    public String toXml() {
        return TEMPLATE.render(Map.ofEntries(
                Map.entry("messageId", messageId),
                Map.entry("createdAt", createdAt.toString()),
                Map.entry("endToEndId", endToEndId),
                Map.entry("transactionId", transactionId),
                Map.entry("uetr", uetr),
                Map.entry("amount", amount.toPlainString()),
                Map.entry("currency", currency),
                Map.entry("settlementDate", settlementDate.toString()),
                Map.entry("debtorName", debtorName),
                Map.entry("debtorIban", debtorIban),
                Map.entry("debtorAgentBic", debtorAgentBic),
                Map.entry("creditorName", creditorName),
                Map.entry("creditorIban", creditorIban),
                Map.entry("creditorAgentBic", creditorAgentBic),
                Map.entry("remittanceInformation", remittanceInformation)));
    }

    /**
     * @throws InvalidMessageException when the XML is not a single-transaction pacs.008.001.08 with every
     *                                 field this project needs
     */
    public static Pacs008 parse(String xml) {
        Iso20022Xml document = Iso20022Xml.parse(xml, NAMESPACE, "pacs.008");
        String numberOfTransactions = document.required(HEADER + "NbOfTxs");
        if (!"1".equals(numberOfTransactions)) {
            throw new InvalidMessageException("A pacs.008 must carry exactly one transaction, not " + numberOfTransactions);
        }
        try {
            return new Pacs008(
                    document.required(HEADER + "MsgId"),
                    Fields.instant("CreDtTm", document.required(HEADER + "CreDtTm")),
                    document.required(TX + "PmtId/EndToEndId"),
                    document.required(TX + "PmtId/TxId"),
                    document.required(TX + "PmtId/UETR"),
                    Fields.decimal("IntrBkSttlmAmt", document.required(TX + "IntrBkSttlmAmt")),
                    document.required(TX + "IntrBkSttlmAmt/@Ccy"),
                    Fields.date("IntrBkSttlmDt", document.required(TX + "IntrBkSttlmDt")),
                    document.required(TX + "Dbtr/Nm"),
                    document.required(TX + "DbtrAcct/Id/IBAN"),
                    document.required(TX + "DbtrAgt/FinInstnId/BICFI"),
                    document.required(TX + "Cdtr/Nm"),
                    document.required(TX + "CdtrAcct/Id/IBAN"),
                    document.required(TX + "CdtrAgt/FinInstnId/BICFI"),
                    document.required(TX + "RmtInf/Ustrd"));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Invalid pacs.008: " + e.getMessage(), e);
        }
    }
}
