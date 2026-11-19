package dev.tkachuk.integration.outgoing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.integration.outgoing.Answers.Action;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnswersTest {

    private static final String[] REASONS = {null, StatusReason.CLOSED_ACCOUNT, StatusReason.PAYMENT_NOT_RECEIVED,
            StatusReason.DUPLICATION};

    @ParameterizedTest(name = "{0}, answer {1} {2}: {3}")
    @CsvSource({
            "NEW, ACSC, -, SETTLE",
            "SENT, ACSC, -, SETTLE",
            "PENDING, ACSC, -, SETTLE",
            "SENT, RJCT, AC04, REJECT",
            "PENDING, RJCT, AC04, REJECT",
            "SENT, RJCT, -, REJECT",
            "SENT, RJCT, AG09, RESEND",
            "PENDING, RJCT, AG09, RESEND",
            "SENT, RJCT, AM05, ASK_AGAIN",
            "NEW, PDNG, -, MARK_PENDING",
            "SENT, PDNG, -, MARK_PENDING",
            "PENDING, PDNG, -, IGNORE",
            "SETTLED, ACSC, -, IGNORE",
            "SETTLED, PDNG, -, IGNORE",
            "SETTLED, RJCT, AM05, IGNORE",
            "SETTLED, RJCT, AC04, CONFLICT",
            "SETTLED, RJCT, AG09, CONFLICT",
            "REJECTED, RJCT, AC04, IGNORE",
            "REJECTED, RJCT, AG09, IGNORE",
            "REJECTED, PDNG, -, IGNORE",
            "REJECTED, ACSC, -, CONFLICT"})
    void shouldDecideByPaymentStateAndAnswer(PaymentState state, TransactionStatus status, String reason,
            Action expected) {
        assertThat(Answers.decide(state, answer(status, "-".equals(reason) ? null : reason))).isEqualTo(expected);
    }

    @Test
    void shouldRejectOnlyOnDefiniteRefusalOfOpenPayment() {
        for (PaymentState state : PaymentState.values()) {
            for (TransactionStatus status : TransactionStatus.values()) {
                for (String reason : REASONS) {
                    boolean definiteRefusal = !state.isFinal() && status == TransactionStatus.RJCT
                            && !StatusReason.PAYMENT_NOT_RECEIVED.equals(reason)
                            && !StatusReason.DUPLICATION.equals(reason);

                    Action action = Answers.decide(state, answer(status, reason));

                    assertThat(action == Action.REJECT).as("%s, answer %s %s: %s", state, status, reason, action)
                            .isEqualTo(definiteRefusal);
                }
            }
        }
    }

    @Test
    void shouldNeverResendPaymentWithFinalAnswer() {
        for (PaymentState state : new PaymentState[] {PaymentState.SETTLED, PaymentState.REJECTED}) {
            for (TransactionStatus status : TransactionStatus.values()) {
                for (String reason : REASONS) {
                    assertThat(Answers.decide(state, answer(status, reason)))
                            .as("%s, answer %s %s", state, status, reason)
                            .isIn(Action.IGNORE, Action.CONFLICT);
                }
            }
        }
    }

    private static Pacs002 answer(TransactionStatus status, String reasonCode) {
        return new Pacs002("67a65fa611fe4db9bc67b8271c441990", Instant.parse("2026-11-17T08:30:05Z"),
                "0f5bd7c23a4e4c1b9d0e6f2a8b1c3d4e", "NOTPROVIDED", "0f5bd7c23a4e4c1b9d0e6f2a8b1c3d4e",
                TestTransfers.TRANSFER_ID.toString(), status, reasonCode);
    }
}
