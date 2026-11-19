package dev.tkachuk.partnerbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.interbank.iso20022.InvalidMessageException;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusReportsTest {

    private static final Instant NOW = Instant.parse("2026-11-17T08:31:15Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final PartnerBankProperties ACCOUNTS =
            new PartnerBankProperties(List.of(TestPayments.CLOSED_ACCOUNT), List.of(TestPayments.SILENT_ACCOUNT));

    private final StatusReports statusReports = new StatusReports(CLOCK, ACCOUNTS);

    @Test
    void acceptsAValidPaymentAndRepeatsItsIds() {
        Pacs008 payment = TestPayments.pacs008("MSG-1");

        Pacs002 report = statusReports.answerPayment(payment.toXml()).orElseThrow();

        assertThat(report.status()).isEqualTo(TransactionStatus.ACSC);
        assertThat(report.reasonCode()).isNull();
        assertThat(report.originalMessageId()).isEqualTo("MSG-1");
        assertThat(report.originalUetr()).isEqualTo(payment.uetr());
        assertThat(report.createdAt()).isEqualTo(NOW);
    }

    @Test
    void refusesAPaymentToAClosedAccountSayingWhy() {
        Pacs008 payment = TestPayments.pacs008("MSG-1", TestPayments.CLOSED_ACCOUNT);

        Pacs002 report = statusReports.answerPayment(payment.toXml()).orElseThrow();

        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isEqualTo(StatusReason.CLOSED_ACCOUNT);
        assertThat(report.originalUetr()).isEqualTo(payment.uetr());
    }

    @Test
    void leavesAPaymentToASilentAccountWithoutAnAnswer() {
        Pacs008 payment = TestPayments.pacs008("MSG-1", TestPayments.SILENT_ACCOUNT);

        assertThat(statusReports.answerPayment(payment.toXml())).isEmpty();
    }

    @Test
    void givesEveryAnswerItsOwnMessageId() {
        Pacs002 first = statusReports.answerPayment(TestPayments.pacs008("MSG-1").toXml()).orElseThrow();
        Pacs002 second = statusReports.answerPayment(TestPayments.pacs008("MSG-2").toXml()).orElseThrow();

        assertThat(second.messageId()).isNotEqualTo(first.messageId());
    }

    @Test
    void answersADuplicateAsBeforeInsteadOfTakingASecondPayment() {
        String xml = TestPayments.pacs008("MSG-1", TestPayments.CLOSED_ACCOUNT).toXml();
        Pacs002 first = statusReports.answerPayment(xml).orElseThrow();

        Pacs002 again = statusReports.answerPayment(xml).orElseThrow();

        assertThat(again.messageId()).isNotEqualTo(first.messageId());
        assertThat(again.repeat(first.messageId(), first.createdAt())).isEqualTo(first);
    }

    @Test
    void knowsACopyByItsMsgIdWhateverElseItCarries() {
        statusReports.answerPayment(TestPayments.pacs008("MSG-1", TestPayments.OPEN_ACCOUNT).toXml());

        Pacs002 again = statusReports.answerPayment(TestPayments.pacs008("MSG-1", TestPayments.CLOSED_ACCOUNT).toXml())
                .orElseThrow();

        assertThat(again.status()).as("the answer to the payment it has under that MsgId").isEqualTo(
                TransactionStatus.ACSC);
    }

    @Test
    void leavesADuplicateOfASilentPaymentWithoutAnAnswerToo() {
        String xml = TestPayments.pacs008("MSG-1", TestPayments.SILENT_ACCOUNT).toXml();
        statusReports.answerPayment(xml);

        assertThat(statusReports.answerPayment(xml)).isEmpty();
    }

    @Test
    void answersAStatusRequestWithThePaymentsOwnAnswer() {
        Pacs008 payment = TestPayments.pacs008("MSG-1", TestPayments.CLOSED_ACCOUNT);
        Pacs002 first = statusReports.answerPayment(payment.toXml()).orElseThrow();

        Pacs002 answer = statusReports.answerStatusRequest(statusRequestAbout(payment).toXml());

        assertThat(answer.messageId()).isNotEqualTo(first.messageId());
        assertThat(answer.repeat(first.messageId(), first.createdAt())).isEqualTo(first);
    }

    @Test
    void answersAStatusRequestAboutAPaymentWithoutAnAnswerWithPending() {
        Pacs008 payment = TestPayments.pacs008("MSG-1", TestPayments.SILENT_ACCOUNT);
        statusReports.answerPayment(payment.toXml());

        Pacs002 answer = statusReports.answerStatusRequest(statusRequestAbout(payment).toXml());

        assertThat(answer.status()).isEqualTo(TransactionStatus.PDNG);
        assertThat(answer.originalMessageId()).isEqualTo("MSG-1");
    }

    @Test
    void answersAStatusRequestAboutAPaymentItNeverReceivedWithAg09() {
        Pacs008 neverSent = TestPayments.pacs008("MSG-LOST");

        Pacs002 answer = statusReports.answerStatusRequest(statusRequestAbout(neverSent).toXml());

        assertThat(answer.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(answer.reasonCode()).isEqualTo(StatusReason.PAYMENT_NOT_RECEIVED);
        assertThat(answer.originalMessageId()).isEqualTo("MSG-LOST");
        assertThat(answer.originalUetr()).isEqualTo(neverSent.uetr());
    }

    @Test
    void forgetsTheOldestPaymentOnceItsMemoryIsFull() {
        StatusReports smallMemory = new StatusReports(CLOCK, ACCOUNTS, 2);
        Pacs008 oldest = TestPayments.pacs008("MSG-1");
        smallMemory.answerPayment(oldest.toXml());
        smallMemory.answerPayment(TestPayments.pacs008("MSG-2").toXml());
        smallMemory.answerPayment(TestPayments.pacs008("MSG-3").toXml());

        Pacs002 answer = smallMemory.answerStatusRequest(statusRequestAbout(oldest).toXml());

        assertThat(answer.reasonCode()).isEqualTo(StatusReason.PAYMENT_NOT_RECEIVED);
    }

    @Test
    void refusesAnythingThatIsNotAPacs008() {
        assertThatThrownBy(() -> statusReports.answerPayment("<Document/>"))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void refusesAStatusRequestThatIsNotAPacs028() {
        String pacs008 = TestPayments.pacs008("MSG-1").toXml();

        assertThatThrownBy(() -> statusReports.answerStatusRequest(pacs008))
                .isInstanceOf(InvalidMessageException.class);
    }

    private static Pacs028 statusRequestAbout(Pacs008 payment) {
        return Pacs028.about(payment, "STATUS-REQUEST-1", NOW.minusSeconds(10));
    }
}
