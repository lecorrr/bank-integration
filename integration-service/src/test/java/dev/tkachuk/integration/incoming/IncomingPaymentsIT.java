package dev.tkachuk.integration.incoming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.TestDatabase;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL of IncomingPayments against a real PostgreSQL (TestDatabase), and the table's own checks. No Spring
 * context: the repository and its table are all that is under test.
 */
class IncomingPaymentsIT {

    private static final Instant RECEIVED_AT = Instant.parse("2026-11-18T09:15:00Z");
    private static final Instant ANSWERED_AT = RECEIVED_AT.plusSeconds(2);
    private static final UUID TRANSFER_ID = UUID.fromString("9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f");

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final IncomingPayments payments = new IncomingPayments(jdbc);

    @BeforeEach
    void startWithAnEmptyTable() {
        jdbc.sql("DELETE FROM incoming_payment").update();
    }

    @Test
    void storesAPaymentOnceAndAfterThatReturnsTheOneStored() {
        Pacs008 payment = TestPayments.another().build();
        IncomingPayment first = IncomingPayment.received(payment, payment.toXml(), RECEIVED_AT);

        Optional<IncomingPayment> stored = payments.insertIfAbsent(first);
        Optional<IncomingPayment> again = payments.insertIfAbsent(
                IncomingPayment.refused(payment, "<copy/>", ANSWERED_AT, StatusReason.INVALID_AMOUNT, "REFUSAL-1"));

        assertThat(stored).contains(first);
        assertThat(again).as("the payment as the first message stored it").contains(first);
        assertThat(payments.findByMessageId("PRTBUAUKXXX", payment.messageId())).contains(first);
        assertThat(payments.findByMessageId("OTHRUAUKXXX", payment.messageId())).as("another bank's MsgId").isEmpty();
    }

    @Test
    void storesARefusalWithItsAnswer() {
        Pacs008 payment = TestPayments.another().currency("USD").build();
        IncomingPayment refused = IncomingPayment.refused(payment, payment.toXml(), RECEIVED_AT,
                StatusReason.NOT_ALLOWED_CURRENCY, "REFUSAL-1");

        assertThat(payments.insertIfAbsent(refused)).contains(refused);
        assertThat(payments.find(refused.uetr()).orElseThrow().answer().reasonCode())
                .isEqualTo(StatusReason.NOT_ALLOWED_CURRENCY);
    }

    @Test
    void storesNothingForAMessageIdThatAnotherPaymentHas() {
        Pacs008 first = TestPayments.another().build();
        payments.insertIfAbsent(IncomingPayment.received(first, first.toXml(), RECEIVED_AT));
        Pacs008 reusingItsId = TestPayments.another().messageId(first.messageId()).build();

        Optional<IncomingPayment> stored =
                payments.insertIfAbsent(IncomingPayment.received(reusingItsId, reusingItsId.toXml(), RECEIVED_AT));

        assertThat(stored).isEmpty();
        assertThat(payments.find(UUID.fromString(reusingItsId.uetr()))).isEmpty();
    }

    @Test
    void anotherBankMayUseTheSameMessageIdForAPaymentOfItsOwn() {
        Pacs008 first = TestPayments.another().build();
        payments.insertIfAbsent(IncomingPayment.received(first, first.toXml(), RECEIVED_AT));
        Pacs008 fromAnotherBank = TestPayments.another().messageId(first.messageId()).debtorAgentBic("OTHRUAUKXXX")
                .build();

        Optional<IncomingPayment> stored = payments.insertIfAbsent(
                IncomingPayment.received(fromAnotherBank, fromAnotherBank.toXml(), RECEIVED_AT));

        assertThat(stored).isPresent();
        assertThat(payments.findByMessageId("OTHRUAUKXXX", first.messageId()).orElseThrow().uetr())
                .isEqualTo(UUID.fromString(fromAnotherBank.uetr()));
    }

    @Test
    void recordsTheAnswerOnceAndKeepsTheFirst() {
        UUID uetr = received();

        boolean first = payments.recordAnswer(uetr, TransactionStatus.ACSC, null, "ANSWER-1", ANSWERED_AT,
                TRANSFER_ID);
        boolean second = payments.recordAnswer(uetr, TransactionStatus.RJCT, StatusReason.NOT_SPECIFIED,
                "ANSWER-2", ANSWERED_AT.plusSeconds(1), UUID.randomUUID());

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        IncomingPayment answered = payments.find(uetr).orElseThrow();
        assertThat(answered.status()).isEqualTo(TransactionStatus.ACSC);
        assertThat(answered.reportId()).isEqualTo("ANSWER-1");
        assertThat(answered.answeredAt()).isEqualTo(ANSWERED_AT);
        assertThat(answered.bankTransferId()).isEqualTo(TRANSFER_ID);
    }

    @Test
    void anAnswerOfOurOwnIsNotReplacedByOneFromBankCore() {
        Pacs008 payment = TestPayments.another().build();
        IncomingPayment refused = IncomingPayment.refused(payment, payment.toXml(), RECEIVED_AT,
                StatusReason.INCORRECT_AGENT, "REFUSAL-1");
        payments.insertIfAbsent(refused);

        assertThat(payments.recordAnswer(refused.uetr(), TransactionStatus.ACSC, null, "ANSWER-1", ANSWERED_AT,
                TRANSFER_ID)).isFalse();
        assertThat(payments.find(refused.uetr())).contains(refused);
    }

    @Test
    void theTableRefusesARefusalWithoutAReasonAndAnAcceptanceWithOne() {
        UUID uetr = received();

        assertThatThrownBy(() -> payments.recordAnswer(uetr, TransactionStatus.RJCT, null, "ANSWER-1",
                ANSWERED_AT, TRANSFER_ID))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("incoming_payment_reason_with_refusal");
        assertThatThrownBy(() -> payments.recordAnswer(uetr, TransactionStatus.ACSC, StatusReason.NOT_SPECIFIED,
                "ANSWER-1", ANSWERED_AT, TRANSFER_ID))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("incoming_payment_reason_with_refusal");
    }

    @Test
    void theTableRefusesAnAnswerWithoutTheReportThatCarriesIt() {
        UUID uetr = received();

        assertThatThrownBy(() -> payments.recordAnswer(uetr, TransactionStatus.ACSC, null, null, ANSWERED_AT,
                TRANSFER_ID))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("incoming_payment_answer_complete");
    }

    private UUID received() {
        Pacs008 payment = TestPayments.another().build();
        return payments.insertIfAbsent(IncomingPayment.received(payment, payment.toXml(), RECEIVED_AT))
                .orElseThrow()
                .uetr();
    }
}
