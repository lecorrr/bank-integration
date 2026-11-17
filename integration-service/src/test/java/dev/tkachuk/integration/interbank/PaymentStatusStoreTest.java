package dev.tkachuk.integration.interbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentStatusStoreTest {

    private final PaymentStatusStore store = new PaymentStatusStore();

    @Test
    void aSentPaymentIsSentUntilItsAnswerArrives() {
        store.sent("MSG-1");

        assertThat(store.statusOf("MSG-1")).hasValue(PaymentStatusStore.SENT);
    }

    @Test
    void anAnswerSetsThePaymentsStatus() {
        store.sent("MSG-1");

        assertThat(store.record(answerTo("MSG-1", TransactionStatus.RJCT))).isTrue();
        assertThat(store.statusOf("MSG-1")).hasValue("RJCT");
    }

    @Test
    void anAnswerToAnUnknownPaymentChangesNothing() {
        assertThat(store.record(answerTo("NEVER-SENT", TransactionStatus.ACSC))).isFalse();
        assertThat(store.statusOf("NEVER-SENT")).isEmpty();
    }

    @Test
    void aForgottenPaymentIsGone() {
        store.sent("MSG-1");

        store.forget("MSG-1");

        assertThat(store.statusOf("MSG-1")).isEmpty();
    }

    @Test
    void keepsOnlyTheNewestPaymentsUpToItsCapacity() {
        PaymentStatusStore small = new PaymentStatusStore(2);

        small.sent("MSG-1");
        small.sent("MSG-2");
        small.sent("MSG-3");

        assertThat(small.statusOf("MSG-1")).isEmpty();
        assertThat(small.statusOf("MSG-2")).hasValue(PaymentStatusStore.SENT);
        assertThat(small.statusOf("MSG-3")).hasValue(PaymentStatusStore.SENT);
    }

    @Test
    void refusesACapacityBelowOne() {
        assertThatThrownBy(() -> new PaymentStatusStore(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    private static Pacs002 answerTo(String originalMessageId, TransactionStatus status) {
        return new Pacs002("ANSWER-1", Instant.parse("2026-11-17T08:30:00Z"), originalMessageId, "E2E-1", "TX-1",
                "3b241101-e2bb-4255-8caf-4136c566a962", status);
    }
}
