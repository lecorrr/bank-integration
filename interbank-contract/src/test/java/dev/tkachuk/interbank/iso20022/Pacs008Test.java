package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class Pacs008Test {

    @Test
    void readsBackEveryFieldItWrites() {
        Pacs008 payment = TestMessages.pacs008();

        assertThat(Pacs008.parse(payment.toXml())).isEqualTo(payment);
    }

    @Test
    void writesThePaymentAsAPacs008Document() {
        String xml = TestMessages.pacs008().toXml();

        assertThat(xml).contains("xmlns=\"" + Pacs008.NAMESPACE + "\"");
        assertThat(xml).contains("<IntrBkSttlmAmt Ccy=\"UAH\">150.00</IntrBkSttlmAmt>");
        assertThat(xml).contains("<NbOfTxs>1</NbOfTxs>");
    }

    @Test
    void escapesTextSoAValueCannotChangeTheMessage() {
        Pacs008 payment = TestMessages.pacs008("Tom & Jerry <Ltd> \"T&J\" 'UA'");

        String xml = payment.toXml();

        assertThat(xml).contains("<Nm>Tom &amp; Jerry &lt;Ltd&gt; &quot;T&amp;J&quot; &apos;UA&apos;</Nm>");
        assertThat(Pacs008.parse(xml)).isEqualTo(payment);
    }

    @Test
    void neverReadsAValueAsAPlaceholder() {
        Pacs008 payment = TestMessages.pacs008("${messageId}");

        assertThat(Pacs008.parse(payment.toXml()).creditorName()).isEqualTo("${messageId}");
    }

    @Test
    void refusesAnotherMessageType() {
        String pacs002 = Pacs002.answering(TestMessages.pacs008(), TransactionStatus.ACSC, "ANSWER-1",
                TestMessages.CREATED_AT).toXml();

        assertThatThrownBy(() -> Pacs008.parse(pacs002))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(Pacs008.NAMESPACE);
    }

    @Test
    void refusesAMessageWithoutARequiredField() {
        String xml = TestMessages.pacs008().toXml().replaceFirst("<UETR>[^<]*</UETR>", "");

        assertThatThrownBy(() -> Pacs008.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("PmtId/UETR");
    }

    @Test
    void refusesMoreThanOneTransaction() {
        String xml = TestMessages.pacs008().toXml().replace("<NbOfTxs>1</NbOfTxs>", "<NbOfTxs>2</NbOfTxs>");

        assertThatThrownBy(() -> Pacs008.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("exactly one transaction");
    }

    @Test
    void refusesAFieldThatBreaksItsRule() {
        String xml = TestMessages.pacs008().toXml().replace("Ccy=\"UAH\"", "Ccy=\"uah\"");

        assertThatThrownBy(() -> Pacs008.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void refusesADoctypeSoNoExternalEntityIsEverRead() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE Document [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">&secret;</Document>
                """;

        assertThatThrownBy(() -> Pacs008.parse(xxe))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("not well-formed");
    }

    @Test
    void refusesTextThatIsNotXml() {
        assertThatThrownBy(() -> Pacs008.parse("150 UAH to Olena, please"))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> Pacs008.parse("  "))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void refusesToBuildAPaymentWithoutAPositiveAmount() {
        Pacs008 valid = TestMessages.pacs008();

        assertThatThrownBy(() -> new Pacs008(valid.messageId(), valid.createdAt(), valid.endToEndId(),
                valid.transactionId(), valid.uetr(), BigDecimal.ZERO, valid.currency(), valid.settlementDate(),
                valid.debtorName(), valid.debtorIban(), valid.debtorAgentBic(), valid.creditorName(),
                valid.creditorIban(), valid.creditorAgentBic(), valid.remittanceInformation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }
}
