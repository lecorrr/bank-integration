package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Camt053Test {

    private static final String UETR = "3b241101-e2bb-4255-8caf-4136c566a962";

    @Test
    void shouldReadBackEveryFieldItWrites() {
        Camt053 statement = TestMessages.statement();

        assertThat(Camt053.parse(statement.toXml())).isEqualTo(statement);
    }

    @Test
    void shouldWriteDocumentThatPassesSchema() throws Exception {
        String xml = TestMessages.statement().toXml();

        TestXml.validate(xml, Camt053.MESSAGE_NAME);
        assertThat(TestXml.rootNamespace(xml)).isEqualTo(Camt053.NAMESPACE);
        assertThat(TestXml.text(xml, "BkToCstmrStmt/Stmt/Acct/Id/IBAN")).isEqualTo("UA853888880000000000000000399");
        assertThat(TestXml.text(xml, "BkToCstmrStmt/Stmt/Bal/Tp/CdOrPrtry/Cd")).isEqualTo("OPBD");
        assertThat(TestXml.text(xml, "BkToCstmrStmt/Stmt/Ntry/Sts/Cd")).isEqualTo("BOOK");
        assertThat(TestXml.text(xml, "BkToCstmrStmt/Stmt/Ntry/BkTxCd/Domn/Fmly/Cd")).isEqualTo("ICDT");
        assertThat(TestXml.text(xml, "BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/Refs/UETR")).isEqualTo(UETR);
    }

    @Test
    void shouldWriteNegativeBalanceAsDebit() throws Exception {
        Camt053 overdrawn = TestMessages.statement(new BigDecimal("-50.00"), List.of(debit("PRTB-0001", "150.00")));

        String xml = overdrawn.toXml();

        assertThat(TestXml.text(xml, "BkToCstmrStmt/Stmt/Bal/CdtDbtInd")).isEqualTo("DBIT");
        assertThat(Camt053.parse(xml).closingBalance()).isEqualByComparingTo("-200.00");
    }

    @Test
    void shouldReadStatementWithoutEntries() {
        Camt053 quietDay = TestMessages.statement(new BigDecimal("1000000.00"), List.of());

        assertThat(Camt053.parse(quietDay.toXml())).isEqualTo(quietDay);
    }

    @Test
    void shouldRejectBalancesThatDontAddUp() {
        Camt053 statement = TestMessages.statement();

        assertThatThrownBy(() -> new Camt053(statement.messageId(), statement.createdAt(), statement.statementId(),
                statement.accountIban(), statement.accountServicerBic(), statement.currency(), statement.date(),
                statement.openingBalance(), statement.openingBalance(), statement.entries()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closing balance");
    }

    @Test
    void shouldReadPaymentBookedTwice() {
        Camt053 statement = TestMessages.statement(new BigDecimal("1000000.00"),
                List.of(debit("PRTB-0001", "150.00"), debit("PRTB-0002", "150.00")));

        assertThat(Camt053.parse(statement.toXml()).entries()).hasSize(2);
    }

    @Test
    void shouldRejectReferenceUsedTwice() {
        List<Camt053.Entry> entries = List.of(debit("PRTB-0001", "150.00"), debit("PRTB-0001", "20.00"));

        assertThatThrownBy(() -> TestMessages.statement(new BigDecimal("1000000.00"), entries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("used twice");
    }

    @Test
    void shouldRejectFractionOfKopiyka() {
        assertThatThrownBy(() -> debit("PRTB-0001", "150.005"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than two decimals");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "a pending entry | <Cd>BOOK</Cd> | <Cd>PDNG</Cd> | not booked",
            "an entry in dollars | <Amt Ccy=\"UAH\">150.00</Amt> | <Amt Ccy=\"USD\">150.00</Amt> | USD",
            "a reversal | <CdtDbtInd>DBIT</CdtDbtInd> | <CdtDbtInd>DBIT</CdtDbtInd><RvslInd>true</RvslInd> | reversal",
            "an entry booked the next day | (?s)(<BookgDt>\\s*<Dt>)2026-11-17 | $12026-11-18 | another day",
            "a closing balance of the next day | (?s)(<Cd>CLBD</Cd>.*?<Dt>)2026-11-17 | $12026-11-18 | same day",
            "an entry without a UETR | (?s)\\s*<UETR>.*?</UETR> | '' | UETR",
            "an entry without a reference | (?s)\\s*<AcctSvcrRef>.*?</AcctSvcrRef> | '' | AcctSvcrRef",
            "two statements | (?s)(<Stmt>.*</Stmt>) | $1$1 | exactly one"})
    void shouldRejectStatementOutsideOurScheme(String statement, String regex, String replacement, String reason) {
        String xml = TestMessages.statement().toXml().replaceFirst(regex, replacement);

        assertThatThrownBy(() -> Camt053.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void shouldRejectPacs008() {
        String payment = TestMessages.pacs008().toXml();

        assertThatThrownBy(() -> Camt053.parse(payment))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(Camt053.NAMESPACE);
    }

    private static Camt053.Entry debit(String reference, String amount) {
        return new Camt053.Entry(reference, CreditDebit.DBIT, new BigDecimal(amount), UETR, "E2E-20261117-0001");
    }
}
