package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Pacs002Test {

    @Test
    void answersAPaymentWithThatPaymentsIds() {
        Pacs008 payment = TestMessages.pacs008();

        Pacs002 report = Pacs002.answering(payment, TransactionStatus.ACSC, "ANSWER-1", TestMessages.CREATED_AT);

        assertThat(report.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(report.originalEndToEndId()).isEqualTo(payment.endToEndId());
        assertThat(report.originalTransactionId()).isEqualTo(payment.transactionId());
        assertThat(report.originalUetr()).isEqualTo(payment.uetr());
    }

    @Test
    void readsBackEveryFieldItWrites() {
        Pacs002 report = Pacs002.answering(TestMessages.pacs008(), TransactionStatus.RJCT, "ANSWER-2",
                TestMessages.CREATED_AT);

        assertThat(Pacs002.parse(report.toXml())).isEqualTo(report);
    }

    @Test
    void writesAPacs002DocumentForTheOriginalPacs008() {
        String xml = Pacs002.answering(TestMessages.pacs008(), TransactionStatus.ACSC, "ANSWER-3",
                TestMessages.CREATED_AT).toXml();

        assertThat(xml).contains("xmlns=\"" + Pacs002.NAMESPACE + "\"");
        assertThat(xml).contains("<OrgnlMsgNmId>" + Pacs008.MESSAGE_NAME + "</OrgnlMsgNmId>");
        assertThat(xml).contains("<TxSts>ACSC</TxSts>");
    }

    @Test
    void refusesAStatusOutsideTheCodesWeUse() {
        String xml = Pacs002.answering(TestMessages.pacs008(), TransactionStatus.ACSC, "ANSWER-4",
                TestMessages.CREATED_AT).toXml().replace("<TxSts>ACSC</TxSts>", "<TxSts>ACWP</TxSts>");

        assertThatThrownBy(() -> Pacs002.parse(xml))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("ACWP");
    }

    @Test
    void refusesAPacs008() {
        String pacs008 = TestMessages.pacs008().toXml();

        assertThatThrownBy(() -> Pacs002.parse(pacs008))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(Pacs002.NAMESPACE);
    }
}
