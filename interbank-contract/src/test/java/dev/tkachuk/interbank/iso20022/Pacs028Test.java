package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Pacs028Test {

    private static final String REQUEST = "/Document/FIToFIPmtStsReq";

    @Test
    void shouldAskAboutPaymentByItsIds() {
        Pacs008 payment = TestMessages.pacs008();

        Pacs028 request = Pacs028.about(payment, "STATUS-REQUEST-1", TestMessages.CREATED_AT.plusSeconds(60));

        assertThat(request.instructingAgentBic()).isEqualTo(payment.debtorAgentBic());
        assertThat(request.instructedAgentBic()).isEqualTo(payment.creditorAgentBic());
        assertThat(request.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(request.originalCreatedAt()).isEqualTo(payment.createdAt());
        assertThat(request.originalEndToEndId()).isEqualTo(payment.endToEndId());
        assertThat(request.originalTransactionId()).isEqualTo(payment.transactionId());
        assertThat(request.originalUetr()).isEqualTo(payment.uetr());
    }

    @Test
    void shouldReadBackEveryFieldItWrites() {
        Pacs028 request = TestMessages.pacs028();

        assertThat(Pacs028.parse(request.toXml())).isEqualTo(request);
    }

    @Test
    void shouldWriteDocumentThatPassesSchema() throws Exception {
        String xml = TestMessages.pacs028().toXml();

        TestXml.validate(xml, Pacs028.MESSAGE_NAME);
        assertThat(TestXml.rootNamespace(xml)).isEqualTo(Pacs028.NAMESPACE);
        assertThat(TestXml.text(xml, "FIToFIPmtStsReq/GrpHdr/InstgAgt/FinInstnId/BICFI")).isEqualTo("BNKCUAUKXXX");
        assertThat(TestXml.text(xml, "FIToFIPmtStsReq/GrpHdr/InstdAgt/FinInstnId/BICFI")).isEqualTo("PRTBUAUKXXX");
        assertThat(TestXml.text(xml, "FIToFIPmtStsReq/TxInf/OrgnlGrpInf/OrgnlMsgNmId"))
                .isEqualTo(Pacs008.MESSAGE_NAME);
    }

    @ParameterizedTest(name = "without {0}")
    @CsvSource({
            "InstdAgt, /GrpHdr/InstdAgt",
            "OrgnlGrpInf, /TxInf/OrgnlGrpInf",
            "OrgnlCreDtTm, /TxInf/OrgnlGrpInf/OrgnlCreDtTm",
            "OrgnlEndToEndId, /TxInf/OrgnlEndToEndId",
            "OrgnlTxId, /TxInf/OrgnlTxId",
            "OrgnlUETR, /TxInf/OrgnlUETR"})
    void shouldRejectRequestMissingRequiredElement(String element, String path) {
        String xml = without(TestMessages.pacs028().toXml(), element);

        assertThatThrownBy(() -> Pacs028.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("has no " + REQUEST + path);
    }

    @Test
    void shouldRejectRequestAboutOtherMessageType() {
        String xml = TestMessages.pacs028().toXml().replace("<OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId>",
                "<OrgnlMsgNmId>pacs.009.001.08</OrgnlMsgNmId>");

        assertThatThrownBy(() -> Pacs028.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("must ask about a " + Pacs008.MESSAGE_NAME);
    }

    @Test
    void shouldRejectRequestAboutMoreThanOnePayment() {
        String xml = TestMessages.pacs028().toXml();
        String end = "</TxInf>";
        String transaction = xml.substring(xml.indexOf("<TxInf>"), xml.indexOf(end) + end.length());

        assertThatThrownBy(() -> Pacs028.parse(xml.replace(transaction, transaction + transaction)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("exactly one " + REQUEST + "/TxInf, not 2");
    }

    @Test
    void shouldRejectStatusReport() {
        String pacs002 = Pacs002.notReceived(TestMessages.pacs028(), "ANSWER-1", TestMessages.CREATED_AT).toXml();

        assertThatThrownBy(() -> Pacs028.parse(pacs002))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(Pacs028.NAMESPACE);
    }

    @Test
    void shouldRejectInvalidBic() {
        Pacs008 payment = TestMessages.pacs008();

        assertThatThrownBy(() -> new Pacs028("STATUS-REQUEST-1", TestMessages.CREATED_AT, "bnkcuaukxxx",
                payment.creditorAgentBic(), payment.messageId(), payment.createdAt(), payment.endToEndId(),
                payment.transactionId(), payment.uetr()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instructingAgentBic");
    }

    private static String without(String xml, String element) {
        return xml.replaceFirst("(?s)\\s*<" + element + ">.*?</" + element + ">", "");
    }
}
