package dev.tkachuk.integration.incoming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.TestDatabase;
import dev.tkachuk.integration.screening.Screening;
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
    void shouldStorePaymentOnceAndReturnStoredOne() {
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
    void shouldStoreRefusalWithItsAnswer() {
        Pacs008 payment = TestPayments.another().currency("USD").build();
        IncomingPayment refused = IncomingPayment.refused(payment, payment.toXml(), RECEIVED_AT,
                StatusReason.NOT_ALLOWED_CURRENCY, "REFUSAL-1");

        assertThat(payments.insertIfAbsent(refused)).contains(refused);
        assertThat(payments.find(refused.uetr()).orElseThrow().answer().reasonCode())
                .isEqualTo(StatusReason.NOT_ALLOWED_CURRENCY);
    }

    @Test
    void shouldNotStorePaymentWithTakenMessageId() {
        Pacs008 first = TestPayments.another().build();
        payments.insertIfAbsent(IncomingPayment.received(first, first.toXml(), RECEIVED_AT));
        Pacs008 reusingItsId = TestPayments.another().messageId(first.messageId()).build();

        Optional<IncomingPayment> stored =
                payments.insertIfAbsent(IncomingPayment.received(reusingItsId, reusingItsId.toXml(), RECEIVED_AT));

        assertThat(stored).isEmpty();
        assertThat(payments.find(UUID.fromString(reusingItsId.uetr()))).isEmpty();
    }

    @Test
    void shouldAllowSameMessageIdFromAnotherBank() {
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
    void shouldRecordAnswerOnceAndKeepFirst() {
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
    void shouldNotReplaceOwnAnswerWithBankCoreDecision() {
        Pacs008 payment = TestPayments.another().build();
        IncomingPayment refused = IncomingPayment.refused(payment, payment.toXml(), RECEIVED_AT,
                StatusReason.INCORRECT_AGENT, "REFUSAL-1");
        payments.insertIfAbsent(refused);

        assertThat(payments.recordAnswer(refused.uetr(), TransactionStatus.ACSC, null, "ANSWER-1", ANSWERED_AT,
                TRANSFER_ID)).isFalse();
        assertThat(payments.find(refused.uetr())).contains(refused);
    }

    @Test
    void shouldEnforceReasonOnlyForRejection() {
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
    void shouldEnforceReportIdForAnswer() {
        UUID uetr = received();

        assertThatThrownBy(() -> payments.recordAnswer(uetr, TransactionStatus.ACSC, null, null, ANSWERED_AT,
                TRANSFER_ID))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("incoming_payment_answer_complete");
    }

    @Test
    void shouldStoreClearScreeningOnce() {
        UUID uetr = received();

        IncomingPayment cleared = payments.recordCleared(uetr, screening(Screening.Outcome.CLEAR, RECEIVED_AT));
        IncomingPayment again = payments.recordCleared(uetr, screening(Screening.Outcome.CLEAR, ANSWERED_AT));

        assertThat(cleared.screening()).isEqualTo(screening(Screening.Outcome.CLEAR, RECEIVED_AT));
        assertThat(cleared.answered()).isFalse();
        assertThat(again).isEqualTo(cleared);
    }

    @Test
    void shouldStoreHitWithRr04Rejection() {
        UUID uetr = received();

        IncomingPayment refused = payments.recordSanctionsRefusal(uetr,
                screening(Screening.Outcome.HIT, RECEIVED_AT), "ANSWER-1", ANSWERED_AT);
        IncomingPayment cleared = payments.recordCleared(uetr, screening(Screening.Outcome.CLEAR, ANSWERED_AT));

        assertThat(refused.outcome()).isEqualTo("RJCT " + StatusReason.REGULATORY_REASON);
        assertThat(refused.reportId()).isEqualTo("ANSWER-1");
        assertThat(refused.answeredAt()).isEqualTo(ANSWERED_AT);
        assertThat(refused.screening()).isEqualTo(screening(Screening.Outcome.HIT, RECEIVED_AT));
        assertThat(cleared).as("a later clear").isEqualTo(refused);
        assertThat(payments.recordAnswer(uetr, TransactionStatus.ACSC, null, "ANSWER-2", ANSWERED_AT, TRANSFER_ID))
                .as("bank-core's decision").isFalse();
    }

    @Test
    void shouldNotScreenPaymentAlreadyRejected() {
        Pacs008 payment = TestPayments.another().build();
        UUID uetr = payments.insertIfAbsent(IncomingPayment.refused(payment, payment.toXml(), RECEIVED_AT,
                StatusReason.NOT_ALLOWED_CURRENCY, "ANSWER-1")).orElseThrow().uetr();

        IncomingPayment after = payments.recordCleared(uetr, screening(Screening.Outcome.CLEAR, ANSWERED_AT));

        assertThat(after.screening()).isNull();
        assertThat(after.outcome()).isEqualTo("RJCT " + StatusReason.NOT_ALLOWED_CURRENCY);
    }

    @Test
    void shouldNotMixUpClearAndHit() {
        UUID uetr = received();

        assertThatThrownBy(() -> payments.recordCleared(uetr, screening(Screening.Outcome.HIT, ANSWERED_AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payments.recordSanctionsRefusal(uetr,
                screening(Screening.Outcome.CLEAR, ANSWERED_AT), "ANSWER-1", ANSWERED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldEnforceRejectionForHit() {
        UUID uetr = received();

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE incoming_payment
                        SET screening_outcome = 'HIT', screening_id = 'SCR-1', screened_at = now()
                        WHERE uetr = :uetr""")
                .param("uetr", uetr)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("incoming_payment_hit_refused");
    }

    private static Screening screening(Screening.Outcome outcome, Instant at) {
        return new Screening(outcome, "SCR-" + outcome, at);
    }

    private UUID received() {
        Pacs008 payment = TestPayments.another().build();
        return payments.insertIfAbsent(IncomingPayment.received(payment, payment.toXml(), RECEIVED_AT))
                .orElseThrow()
                .uetr();
    }
}
