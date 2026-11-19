package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Pacs002Test {

    private static final String TRANSACTION = "/Document/FIToFIPmtStsRpt/TxInfAndSts";

    @Test
    void shouldAnswerPaymentWithItsIds() {
        Pacs008 payment = TestMessages.pacs008();

        Pacs002 report = Pacs002.answering(payment, TransactionStatus.ACSC, "ANSWER-1", TestMessages.CREATED_AT);

        assertThat(report.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(report.originalEndToEndId()).isEqualTo(payment.endToEndId());
        assertThat(report.originalTransactionId()).isEqualTo(payment.transactionId());
        assertThat(report.originalUetr()).isEqualTo(payment.uetr());
    }

    @Test
    void shouldAnswerAg09WithStatusRequestIds() throws Exception {
        Pacs028 request = TestMessages.pacs028();

        Pacs002 report = Pacs002.notReceived(request, "ANSWER-1", TestMessages.CREATED_AT);

        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isEqualTo(StatusReason.PAYMENT_NOT_RECEIVED);
        assertThat(report.originalMessageId()).isEqualTo(request.originalMessageId());
        assertThat(report.originalEndToEndId()).isEqualTo(request.originalEndToEndId());
        assertThat(report.originalTransactionId()).isEqualTo(request.originalTransactionId());
        assertThat(report.originalUetr()).isEqualTo(request.originalUetr());
        TestXml.validate(report.toXml(), Pacs002.MESSAGE_NAME);
    }

    @Test
    void shouldRejectRequestedMessageWithStatusRequestIds() throws Exception {
        Pacs028 request = TestMessages.pacs028();

        Pacs002 report = Pacs002.rejecting(request, StatusReason.DUPLICATION, "ANSWER-1", TestMessages.CREATED_AT);

        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isEqualTo(StatusReason.DUPLICATION);
        assertThat(report.originalMessageId()).isEqualTo(request.originalMessageId());
        assertThat(report.originalUetr()).isEqualTo(request.originalUetr());
        TestXml.validate(report.toXml(), Pacs002.MESSAGE_NAME);
        assertThatThrownBy(() -> Pacs002.rejecting(request, null, "ANSWER-1", TestMessages.CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode");
    }

    @Test
    void shouldRepeatAnswerWithNewIdAndTime() {
        Pacs002 first = Pacs002.rejecting(TestMessages.pacs008(), "AC04", "ANSWER-1", TestMessages.CREATED_AT);

        Pacs002 again = first.repeat("ANSWER-2", TestMessages.CREATED_AT.plusSeconds(90));

        assertThat(again.messageId()).isEqualTo("ANSWER-2");
        assertThat(again.createdAt()).isEqualTo(TestMessages.CREATED_AT.plusSeconds(90));
        assertThat(again.repeat(first.messageId(), first.createdAt())).isEqualTo(first);
    }

    @Test
    void shouldReadBackEveryFieldItWrites() {
        Pacs002 report = answer(TransactionStatus.ACSC);

        assertThat(Pacs002.parse(report.toXml())).isEqualTo(report);
    }

    @Test
    void shouldKeepRejectionReasonInRoundTrip() throws Exception {
        Pacs002 report = Pacs002.rejecting(TestMessages.pacs008(), "AC04", "ANSWER-1", TestMessages.CREATED_AT);
        String xml = report.toXml();

        TestXml.validate(xml, Pacs002.MESSAGE_NAME);
        assertThat(TestXml.text(xml, "FIToFIPmtStsRpt/TxInfAndSts/TxSts")).isEqualTo("RJCT");
        assertThat(TestXml.text(xml, "FIToFIPmtStsRpt/TxInfAndSts/StsRsnInf/Rsn/Cd")).isEqualTo("AC04");
        assertThat(Pacs002.parse(xml)).isEqualTo(report);
    }

    @Test
    void shouldReadProprietaryReasonWithoutCode() {
        String xml = Pacs002.rejecting(TestMessages.pacs008(), "AC04", "ANSWER-1", TestMessages.CREATED_AT).toXml()
                .replace("<Cd>AC04</Cd>", "<Prtry>Account closed</Prtry>");

        Pacs002 report = Pacs002.parse(xml);

        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isNull();
    }

    @Test
    void shouldRequireReasonCodeOfOneToFourCharacters() {
        Pacs008 payment = TestMessages.pacs008();

        for (String reason : new String[] {null, "", "AC045"}) {
            assertThatThrownBy(() -> Pacs002.rejecting(payment, reason, "ANSWER-1", TestMessages.CREATED_AT))
                    .as("reason <%s>", reason)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reasonCode");
        }
    }

    @Test
    void shouldWriteDocumentThatPassesSchema() throws Exception {
        String xml = answer(TransactionStatus.ACSC).toXml();

        TestXml.validate(xml, Pacs002.MESSAGE_NAME);
        assertThat(TestXml.rootNamespace(xml)).isEqualTo(Pacs002.NAMESPACE);
        assertThat(TestXml.text(xml, "FIToFIPmtStsRpt/OrgnlGrpInfAndSts/OrgnlMsgNmId"))
                .isEqualTo(Pacs008.MESSAGE_NAME);
        assertThat(TestXml.text(xml, "FIToFIPmtStsRpt/TxInfAndSts/TxSts")).isEqualTo("ACSC");
    }

    @Test
    void shouldRejectUnsupportedStatus() {
        String xml = answer(TransactionStatus.ACSC).toXml().replace("<TxSts>ACSC</TxSts>", "<TxSts>ACWP</TxSts>");

        assertThatThrownBy(() -> Pacs002.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("ACWP");
    }

    @Test
    void shouldRejectPacs008() {
        String pacs008 = TestMessages.pacs008().toXml();

        assertThatThrownBy(() -> Pacs002.parse(pacs008))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(Pacs002.NAMESPACE);
    }

    @Test
    void shouldRejectReportMissingRequiredField() {
        String xml = answer(TransactionStatus.ACSC).toXml().replaceFirst("\\s*<OrgnlUETR>[^<]*</OrgnlUETR>", "");

        assertThatThrownBy(() -> Pacs002.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("has no " + TRANSACTION + "/OrgnlUETR");
    }

    @Test
    void shouldRejectReportOnMoreThanOneTransaction() {
        String xml = answer(TransactionStatus.ACSC).toXml();
        String end = "</TxInfAndSts>";
        String transaction = xml.substring(xml.indexOf("<TxInfAndSts>"), xml.indexOf(end) + end.length());

        assertThatThrownBy(() -> Pacs002.parse(xml.replace(transaction, transaction + transaction)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("exactly one " + TRANSACTION + ", not 2");
    }

    private static Pacs002 answer(TransactionStatus status) {
        return Pacs002.answering(TestMessages.pacs008(), status, "ANSWER-1", TestMessages.CREATED_AT);
    }
}
