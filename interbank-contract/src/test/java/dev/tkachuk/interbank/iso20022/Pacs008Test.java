package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Pacs008Test {

    private static final String TRANSACTION = "/Document/FIToFICstmrCdtTrf/CdtTrfTxInf";

    @Test
    void shouldReadBackEveryFieldItWrites() {
        Pacs008 payment = TestMessages.pacs008();

        assertThat(Pacs008.parse(payment.toXml())).isEqualTo(payment);
    }

    @Test
    void shouldWriteDocumentThatPassesSchema() throws Exception {
        String xml = TestMessages.pacs008().toXml();

        TestXml.validate(xml, Pacs008.MESSAGE_NAME);
        assertThat(TestXml.rootNamespace(xml)).isEqualTo(Pacs008.NAMESPACE);
        assertThat(TestXml.text(xml, "FIToFICstmrCdtTrf/GrpHdr/NbOfTxs")).isEqualTo("1");
        assertThat(TestXml.text(xml, "FIToFICstmrCdtTrf/CdtTrfTxInf/IntrBkSttlmAmt")).isEqualTo("150.00");
    }

    @Test
    void shouldEscapeMarkupCharacters() throws Exception {
        String name = "Tom & Jerry <Ltd> \"T&J\" 'UA'";
        Pacs008 payment = TestMessages.pacs008(name);

        String xml = payment.toXml();

        assertThat(TestXml.text(xml, "FIToFICstmrCdtTrf/CdtTrfTxInf/Cdtr/Nm")).isEqualTo(name);
        assertThat(Pacs008.parse(xml)).isEqualTo(payment);
    }

    @Test
    void shouldRejectOtherMessageType() {
        String pacs002 = Pacs002.answering(TestMessages.pacs008(), TransactionStatus.ACSC, "ANSWER-1",
                TestMessages.CREATED_AT).toXml();

        assertThatThrownBy(() -> Pacs008.parse(pacs002))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(Pacs008.NAMESPACE);
    }

    static Stream<Arguments> schemaViolations() {
        return Stream.of(
                Arguments.of("a lower-case currency code",
                        replace("Ccy=\"UAH\"", "Ccy=\"uah\""), TRANSACTION + "/IntrBkSttlmAmt"),
                Arguments.of("a 36-character message id",
                        replaceFirst("<MsgId>[^<]*</MsgId>", "<MsgId>" + "M".repeat(36) + "</MsgId>"),
                        "/Document/FIToFICstmrCdtTrf/GrpHdr/MsgId"),
                Arguments.of("no charge bearer, noticed at the element after it",
                        replaceFirst("\\s*<ChrgBr>SLEV</ChrgBr>", ""), TRANSACTION + "/Dbtr"),
                Arguments.of("an element the schema does not have",
                        replace("</Dbtr>", "<Nickname>Artie</Nickname></Dbtr>"), TRANSACTION + "/Dbtr/Nickname"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaViolations")
    void shouldRejectMessageThatFailsSchema(String violation, UnaryOperator<String> breakIt, String where) {
        String xml = breakIt.apply(TestMessages.pacs008().toXml());

        assertThatThrownBy(() -> Pacs008.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("breaks its schema at " + where + " (cvc-");
    }

    @Test
    void shouldNotQuoteInvalidValue() {
        String iban = TestMessages.pacs008().debtorIban();
        String xml = TestMessages.pacs008().toXml().replace(iban, iban.substring(0, 4) + " " + iban.substring(4));

        assertThatThrownBy(() -> Pacs008.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(TRANSACTION + "/DbtrAcct/Id/IBAN")
                .hasMessageNotContaining(iban.substring(4))
                .hasNoCause();
    }

    @Test
    void shouldRejectMessageMissingRequiredField() {
        String xml = TestMessages.pacs008().toXml().replaceFirst("\\s*<UETR>[^<]*</UETR>", "");

        assertThatThrownBy(() -> Pacs008.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("has no " + TRANSACTION + "/PmtId/UETR");
    }

    @Test
    void shouldRejectMoreThanOneTransaction() {
        String xml = TestMessages.pacs008().toXml().replace("<NbOfTxs>1</NbOfTxs>", "<NbOfTxs>2</NbOfTxs>");

        assertThatThrownBy(() -> Pacs008.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("exactly one transaction");
    }

    @Test
    void shouldRejectMoreThanOneRemittanceLine() {
        String xml = TestMessages.pacs008().toXml().replace("</Ustrd>", "</Ustrd><Ustrd>Thank you</Ustrd>");

        assertThatThrownBy(() -> Pacs008.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("exactly one " + TRANSACTION + "/RmtInf/Ustrd, not 2");
    }

    @Test
    void shouldRejectBlankId() {
        String xml = TestMessages.pacs008().toXml().replaceFirst("<TxId>[^<]*</TxId>", "<TxId>   </TxId>");

        assertThatThrownBy(() -> Pacs008.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("transactionId must be 1 to 35 characters");
    }

    @Test
    void shouldNotWriteAmountSchemaDoesNotAllow() {
        Pacs008 valid = TestMessages.pacs008();
        Pacs008 sixDecimals = new Pacs008(valid.messageId(), valid.createdAt(), valid.endToEndId(),
                valid.transactionId(), valid.uetr(), new BigDecimal("150.000001"), valid.currency(),
                valid.settlementDate(), valid.debtorName(), valid.debtorIban(), valid.debtorAgentBic(),
                valid.creditorName(), valid.creditorIban(), valid.creditorAgentBic(), valid.remittanceInformation());

        assertThatThrownBy(sixDecimals::toXml)
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(TRANSACTION + "/IntrBkSttlmAmt (cvc-fractionDigits-valid");
    }

    @Test
    void shouldRejectDoctype() {
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
    void shouldRejectTextThatIsNotXml() {
        assertThatThrownBy(() -> Pacs008.parse("150 UAH to Olena, please"))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> Pacs008.parse("  "))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void shouldRequirePositiveAmount() {
        Pacs008 valid = TestMessages.pacs008();

        assertThatThrownBy(() -> new Pacs008(valid.messageId(), valid.createdAt(), valid.endToEndId(),
                valid.transactionId(), valid.uetr(), BigDecimal.ZERO, valid.currency(), valid.settlementDate(),
                valid.debtorName(), valid.debtorIban(), valid.debtorAgentBic(), valid.creditorName(),
                valid.creditorIban(), valid.creditorAgentBic(), valid.remittanceInformation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    private static UnaryOperator<String> replace(String target, String replacement) {
        return xml -> xml.replace(target, replacement);
    }

    private static UnaryOperator<String> replaceFirst(String regex, String replacement) {
        return xml -> xml.replaceFirst(regex, replacement);
    }
}
