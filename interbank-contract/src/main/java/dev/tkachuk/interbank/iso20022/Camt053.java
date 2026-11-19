package dev.tkachuk.interbank.iso20022;

import dev.tkachuk.interbank.iso20022.camt053.AccountIdentification4Choice;
import dev.tkachuk.interbank.iso20022.camt053.AccountStatement9;
import dev.tkachuk.interbank.iso20022.camt053.ActiveOrHistoricCurrencyAndAmount;
import dev.tkachuk.interbank.iso20022.camt053.BalanceType10Choice;
import dev.tkachuk.interbank.iso20022.camt053.BalanceType13;
import dev.tkachuk.interbank.iso20022.camt053.BankToCustomerStatementV08;
import dev.tkachuk.interbank.iso20022.camt053.BankTransactionCodeStructure4;
import dev.tkachuk.interbank.iso20022.camt053.BankTransactionCodeStructure5;
import dev.tkachuk.interbank.iso20022.camt053.BankTransactionCodeStructure6;
import dev.tkachuk.interbank.iso20022.camt053.BranchAndFinancialInstitutionIdentification6;
import dev.tkachuk.interbank.iso20022.camt053.CashAccount39;
import dev.tkachuk.interbank.iso20022.camt053.CashBalance8;
import dev.tkachuk.interbank.iso20022.camt053.CreditDebitCode;
import dev.tkachuk.interbank.iso20022.camt053.DateAndDateTime2Choice;
import dev.tkachuk.interbank.iso20022.camt053.Document;
import dev.tkachuk.interbank.iso20022.camt053.EntryDetails9;
import dev.tkachuk.interbank.iso20022.camt053.EntryStatus1Choice;
import dev.tkachuk.interbank.iso20022.camt053.EntryTransaction10;
import dev.tkachuk.interbank.iso20022.camt053.FinancialInstitutionIdentification18;
import dev.tkachuk.interbank.iso20022.camt053.GroupHeader81;
import dev.tkachuk.interbank.iso20022.camt053.ObjectFactory;
import dev.tkachuk.interbank.iso20022.camt053.ReportEntry10;
import dev.tkachuk.interbank.iso20022.camt053.TransactionReferences6;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * camt.053.001.08: one day's statement of one account, from the bank that keeps the account. Our scheme allows
 * one statement per message, booked entries only, and one payment per entry, named by its UETR.
 */
public record Camt053(
        String messageId,
        Instant createdAt,
        String statementId,
        String accountIban,
        String accountServicerBic,
        String currency,
        LocalDate date,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        List<Entry> entries) {

    public static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08";
    public static final String MESSAGE_NAME = "camt.053.001.08";

    private static final Iso20022Xml<Document> XML =
            new Iso20022Xml<>(MESSAGE_NAME, NAMESPACE, Document.class, new ObjectFactory()::createDocument);
    private static final String STATEMENT = "/Document/BkToCstmrStmt/Stmt";
    private static final String OPENING_BOOKED = "OPBD";
    private static final String CLOSING_BOOKED = "CLBD";
    private static final String BOOKED = "BOOK";

    public Camt053 {
        Fields.max35("messageId", messageId);
        Fields.required("createdAt", createdAt);
        Fields.max35("statementId", statementId);
        Fields.iban("accountIban", accountIban);
        Fields.bic("accountServicerBic", accountServicerBic);
        Fields.currency(currency);
        Fields.required("date", date);
        requireKopiyky("openingBalance", openingBalance);
        requireKopiyky("closingBalance", closingBalance);
        Fields.required("entries", entries);
        entries = List.copyOf(entries);
        // A payment booked twice is allowed here: it is the bank's mistake, and reconciliation reports it.
        Set<String> references = new HashSet<>();
        BigDecimal balance = openingBalance;
        for (Entry entry : entries) {
            if (!references.add(entry.reference())) {
                throw new IllegalArgumentException("entry reference " + entry.reference() + " is used twice");
            }
            balance = balance.add(entry.signedAmount());
        }
        if (balance.compareTo(closingBalance) != 0) {
            throw new IllegalArgumentException("closing balance " + closingBalance
                    + " is not the opening balance plus the entries (" + balance + ")");
        }
    }

    /** One booked payment. */
    public record Entry(String reference, CreditDebit creditDebit, BigDecimal amount, String uetr, String endToEndId) {

        public Entry {
            Fields.max35("reference", reference);
            Fields.required("creditDebit", creditDebit);
            Fields.positiveAmount(amount);
            requireKopiyky("amount", amount);
            Fields.uetr(uetr);
            Fields.max35("endToEndId", endToEndId);
        }

        public BigDecimal signedAmount() {
            return creditDebit == CreditDebit.CRDT ? amount : amount.negate();
        }
    }

    public String toXml() {
        GroupHeader81 header = new GroupHeader81();
        header.setMsgId(messageId);
        header.setCreDtTm(XmlDates.toXml(createdAt));

        AccountStatement9 statement = new AccountStatement9();
        statement.setId(statementId);
        statement.setCreDtTm(XmlDates.toXml(createdAt));
        statement.setAcct(account());
        statement.getBal().add(balance(OPENING_BOOKED, openingBalance));
        statement.getBal().add(balance(CLOSING_BOOKED, closingBalance));
        for (Entry entry : entries) {
            statement.getNtry().add(entry(entry));
        }

        BankToCustomerStatementV08 message = new BankToCustomerStatementV08();
        message.setGrpHdr(header);
        message.getStmt().add(statement);
        Document document = new Document();
        document.setBkToCstmrStmt(message);
        return XML.write(document);
    }

    public static Camt053 parse(String xml) {
        BankToCustomerStatementV08 message = XML.read(xml).getBkToCstmrStmt();
        AccountStatement9 statement = XML.single(message.getStmt(), STATEMENT);
        CashAccount39 account = statement.getAcct();
        String currency = XML.required(account.getCcy(), STATEMENT + "/Acct/Ccy");
        CashBalance8 opening = balanceOf(statement, OPENING_BOOKED);
        CashBalance8 closing = balanceOf(statement, CLOSING_BOOKED);
        try {
            LocalDate date = dateOf(opening.getDt(), STATEMENT + "/Bal/Dt");
            if (!date.equals(dateOf(closing.getDt(), STATEMENT + "/Bal/Dt"))) {
                throw new InvalidMessageException("The opening and closing balances of a " + MESSAGE_NAME
                        + " must be on the same day");
            }
            List<Entry> entries = new ArrayList<>();
            for (ReportEntry10 entry : statement.getNtry()) {
                entries.add(entryOf(entry, date, currency));
            }
            return new Camt053(
                    message.getGrpHdr().getMsgId(),
                    XmlDates.toInstant("CreDtTm", message.getGrpHdr().getCreDtTm()),
                    statement.getId(),
                    XML.required(account.getId().getIBAN(), STATEMENT + "/Acct/Id/IBAN"),
                    XML.required(XML.required(account.getSvcr(), STATEMENT + "/Acct/Svcr").getFinInstnId().getBICFI(),
                            STATEMENT + "/Acct/Svcr/FinInstnId/BICFI"),
                    currency,
                    date,
                    signed(opening.getAmt(), opening.getCdtDbtInd(), currency),
                    signed(closing.getAmt(), closing.getCdtDbtInd(), currency),
                    entries);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Invalid " + MESSAGE_NAME + ": " + e.getMessage(), e);
        }
    }

    private CashAccount39 account() {
        AccountIdentification4Choice id = new AccountIdentification4Choice();
        id.setIBAN(accountIban);
        FinancialInstitutionIdentification18 institution = new FinancialInstitutionIdentification18();
        institution.setBICFI(accountServicerBic);
        BranchAndFinancialInstitutionIdentification6 servicer = new BranchAndFinancialInstitutionIdentification6();
        servicer.setFinInstnId(institution);
        CashAccount39 account = new CashAccount39();
        account.setId(id);
        account.setCcy(currency);
        account.setSvcr(servicer);
        return account;
    }

    private CashBalance8 balance(String type, BigDecimal amount) {
        BalanceType10Choice code = new BalanceType10Choice();
        code.setCd(type);
        BalanceType13 balanceType = new BalanceType13();
        balanceType.setCdOrPrtry(code);
        CashBalance8 balance = new CashBalance8();
        balance.setTp(balanceType);
        balance.setAmt(amount(amount.abs()));
        balance.setCdtDbtInd(amount.signum() < 0 ? CreditDebitCode.DBIT : CreditDebitCode.CRDT);
        balance.setDt(day(date));
        return balance;
    }

    private ReportEntry10 entry(Entry entry) {
        EntryStatus1Choice status = new EntryStatus1Choice();
        status.setCd(BOOKED);
        TransactionReferences6 references = new TransactionReferences6();
        references.setEndToEndId(entry.endToEndId());
        references.setUETR(entry.uetr());
        EntryTransaction10 transaction = new EntryTransaction10();
        transaction.setRefs(references);
        EntryDetails9 details = new EntryDetails9();
        details.getTxDtls().add(transaction);

        ReportEntry10 reported = new ReportEntry10();
        reported.setAmt(amount(entry.amount()));
        reported.setCdtDbtInd(CreditDebitCode.valueOf(entry.creditDebit().name()));
        reported.setSts(status);
        reported.setBookgDt(day(date));
        reported.setValDt(day(date));
        reported.setAcctSvcrRef(entry.reference());
        reported.setBkTxCd(transactionCode(entry.creditDebit()));
        reported.getNtryDtls().add(details);
        return reported;
    }

    private ActiveOrHistoricCurrencyAndAmount amount(BigDecimal value) {
        ActiveOrHistoricCurrencyAndAmount amount = new ActiveOrHistoricCurrencyAndAmount();
        amount.setValue(value);
        amount.setCcy(currency);
        return amount;
    }

    private static DateAndDateTime2Choice day(LocalDate date) {
        DateAndDateTime2Choice day = new DateAndDateTime2Choice();
        day.setDt(XmlDates.toXml(date));
        return day;
    }

    // Payments domain, issued or received credit transfers, domestic.
    private static BankTransactionCodeStructure4 transactionCode(CreditDebit creditDebit) {
        BankTransactionCodeStructure6 family = new BankTransactionCodeStructure6();
        family.setCd(creditDebit == CreditDebit.CRDT ? "RCDT" : "ICDT");
        family.setSubFmlyCd("DMCT");
        BankTransactionCodeStructure5 domain = new BankTransactionCodeStructure5();
        domain.setCd("PMNT");
        domain.setFmly(family);
        BankTransactionCodeStructure4 code = new BankTransactionCodeStructure4();
        code.setDomn(domain);
        return code;
    }

    private static CashBalance8 balanceOf(AccountStatement9 statement, String type) {
        List<CashBalance8> found = statement.getBal().stream()
                .filter(balance -> balance.getTp().getCdOrPrtry() != null
                        && type.equals(balance.getTp().getCdOrPrtry().getCd()))
                .toList();
        return XML.single(found, STATEMENT + "/Bal[" + type + "]");
    }

    private static Entry entryOf(ReportEntry10 entry, LocalDate date, String currency) {
        String path = STATEMENT + "/Ntry";
        String reference = XML.required(entry.getAcctSvcrRef(), path + "/AcctSvcrRef");
        if (entry.getSts().getCd() == null || !BOOKED.equals(entry.getSts().getCd())) {
            throw new InvalidMessageException("Entry " + reference + " is not booked (Sts/Cd must be " + BOOKED + ")");
        }
        if (Boolean.TRUE.equals(entry.isRvslInd())) {
            throw new InvalidMessageException("Entry " + reference + " is a reversal, which our scheme does not use");
        }
        if (!date.equals(dateOf(XML.required(entry.getBookgDt(), path + "/BookgDt"), path + "/BookgDt"))) {
            throw new InvalidMessageException("Entry " + reference + " is booked on another day than the statement");
        }
        EntryDetails9 details = XML.single(entry.getNtryDtls(), path + "/NtryDtls");
        EntryTransaction10 transaction = XML.single(details.getTxDtls(), path + "/NtryDtls/TxDtls");
        TransactionReferences6 references = XML.required(transaction.getRefs(), path + "/NtryDtls/TxDtls/Refs");
        return new Entry(
                reference,
                CreditDebit.valueOf(entry.getCdtDbtInd().name()),
                amountIn(entry.getAmt(), currency),
                XML.required(references.getUETR(), path + "/NtryDtls/TxDtls/Refs/UETR"),
                XML.required(references.getEndToEndId(), path + "/NtryDtls/TxDtls/Refs/EndToEndId"));
    }

    private static LocalDate dateOf(DateAndDateTime2Choice value, String path) {
        return XmlDates.toLocalDate(path, XML.required(value.getDt(), path + "/Dt"));
    }

    private static BigDecimal signed(ActiveOrHistoricCurrencyAndAmount amount, CreditDebitCode creditDebit,
            String currency) {
        BigDecimal value = amountIn(amount, currency);
        return creditDebit == CreditDebitCode.DBIT ? value.negate() : value;
    }

    private static BigDecimal amountIn(ActiveOrHistoricCurrencyAndAmount amount, String currency) {
        if (!currency.equals(amount.getCcy())) {
            throw new IllegalArgumentException("an amount is in " + amount.getCcy() + ", the account in " + currency);
        }
        return amount.getValue();
    }

    private static void requireKopiyky(String field, BigDecimal value) {
        Fields.required(field, value);
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(field + " has more than two decimals");
        }
    }
}
