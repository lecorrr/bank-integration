package dev.tkachuk.integration.incoming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.incoming.IncomingTransferDecided.Decision;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncomingPaymentTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-11-18T09:15:02Z");
    private static final Instant ANSWERED_AT = Instant.parse("2026-11-18T09:15:04Z");
    private static final UUID TRANSFER_ID = UUID.fromString("9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f");

    private final Pacs008 payment = TestPayments.payment();
    private final String xml = payment.toXml();

    @Test
    void shouldWaitForBankCoreWhenJustReceived() {
        IncomingPayment received = IncomingPayment.received(payment, xml, RECEIVED_AT);

        assertThat(received.uetr()).isEqualTo(UUID.fromString(payment.uetr()));
        assertThat(received.senderBic()).isEqualTo("PRTBUAUKXXX");
        assertThat(received.messageId()).isEqualTo(payment.messageId());
        assertThat(received.answered()).isFalse();
        assertThatThrownBy(received::answer).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldTreatOwnRefusalAsAnswerWithoutTransfer() {
        IncomingPayment refused = IncomingPayment.refused(payment, xml, RECEIVED_AT,
                StatusReason.NOT_ALLOWED_CURRENCY, "REFUSAL-1");

        assertThat(refused.answered()).isTrue();
        assertThat(refused.outcome()).isEqualTo("RJCT AM03");
        assertThat(refused.bankTransferId()).isNull();
        assertThat(refused.answers(new IncomingTransferDecided(refused.uetr(), TRANSFER_ID, Decision.REJECTED,
                "CURRENCY_MISMATCH", ANSWERED_AT))).isFalse();
    }

    @Test
    void shouldBuildSamePacs002EveryTime() {
        IncomingPayment refused = IncomingPayment.refused(payment, xml, RECEIVED_AT,
                StatusReason.NOT_ALLOWED_CURRENCY, "REFUSAL-1");

        Pacs002 answer = refused.answer();

        assertThat(answer).isEqualTo(Pacs002.rejecting(payment, StatusReason.NOT_ALLOWED_CURRENCY, "REFUSAL-1",
                RECEIVED_AT));
        assertThat(refused.answer().toXml()).isEqualTo(answer.toXml());
    }

    @Test
    void shouldAcceptWithoutReason() {
        IncomingPayment credited = answered(TransactionStatus.ACSC, null);

        Pacs002 answer = credited.answer();

        assertThat(answer.status()).isEqualTo(TransactionStatus.ACSC);
        assertThat(answer.reasonCode()).isNull();
        assertThat(answer.messageId()).isEqualTo("ANSWER-1");
        assertThat(answer.originalMessageId()).isEqualTo(payment.messageId());
        assertThat(answer.createdAt()).isEqualTo(ANSWERED_AT);
    }

    @Test
    void shouldMatchDecisionOnlyWhenStatusReasonAndTransferMatch() {
        IncomingPayment rejected = answered(TransactionStatus.RJCT, StatusReason.INCORRECT_ACCOUNT_NUMBER);
        UUID uetr = rejected.uetr();

        assertThat(rejected.answers(new IncomingTransferDecided(uetr, TRANSFER_ID, Decision.REJECTED,
                "UNKNOWN_ACCOUNT", ANSWERED_AT))).isTrue();
        assertThat(rejected.answers(new IncomingTransferDecided(uetr, TRANSFER_ID, Decision.REJECTED,
                "CURRENCY_MISMATCH", ANSWERED_AT))).as("another reason").isFalse();
        assertThat(rejected.answers(new IncomingTransferDecided(uetr, TRANSFER_ID, Decision.CREDITED, null,
                ANSWERED_AT))).as("the opposite decision").isFalse();
        assertThat(rejected.answers(new IncomingTransferDecided(uetr, UUID.randomUUID(), Decision.REJECTED,
                "UNKNOWN_ACCOUNT", ANSWERED_AT))).as("another transfer").isFalse();
    }

    private IncomingPayment answered(TransactionStatus status, String reasonCode) {
        return new IncomingPayment(UUID.fromString(payment.uetr()), payment.debtorAgentBic(), payment.messageId(), xml,
                RECEIVED_AT, status, reasonCode, "ANSWER-1", ANSWERED_AT, TRANSFER_ID, null);
    }
}
