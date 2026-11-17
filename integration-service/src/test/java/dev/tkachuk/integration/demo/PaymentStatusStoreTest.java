package dev.tkachuk.integration.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.outgoing.TransferStatus;
import dev.tkachuk.integration.outgoing.TransferStatusChanged;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentStatusStoreTest {

    private static final UUID TRANSFER = UUID.fromString("0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e");

    private final PaymentStatusStore store = new PaymentStatusStore();

    @Test
    void aTransferIsRequestedUntilItsStatusArrives() {
        store.requested(TRANSFER.toString());

        assertThat(store.statusOf(TRANSFER.toString())).hasValue(PaymentStatusStore.REQUESTED);
    }

    @Test
    void aStatusEventSetsTheStatus() {
        store.requested(TRANSFER.toString());

        store.record(statusOf(TRANSFER, TransferStatus.REJECTED));

        assertThat(store.statusOf(TRANSFER.toString())).hasValue("REJECTED");
    }

    @Test
    void keepsTheStatusOfATransferTheDemoDidNotRequest() {
        store.record(statusOf(TRANSFER, TransferStatus.SETTLED));

        assertThat(store.statusOf(TRANSFER.toString())).hasValue("SETTLED");
    }

    @Test
    void aForgottenTransferIsGone() {
        store.requested(TRANSFER.toString());

        store.forget(TRANSFER.toString());

        assertThat(store.statusOf(TRANSFER.toString())).isEmpty();
    }

    @Test
    void keepsOnlyTheNewestTransfersUpToItsCapacity() {
        PaymentStatusStore small = new PaymentStatusStore(2);

        small.requested("transfer-1");
        small.requested("transfer-2");
        small.requested("transfer-3");

        assertThat(small.statusOf("transfer-1")).isEmpty();
        assertThat(small.statusOf("transfer-2")).hasValue(PaymentStatusStore.REQUESTED);
        assertThat(small.statusOf("transfer-3")).hasValue(PaymentStatusStore.REQUESTED);
    }

    @Test
    void refusesACapacityBelowOne() {
        assertThatThrownBy(() -> new PaymentStatusStore(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    private static TransferStatusChanged statusOf(UUID transferId, TransferStatus status) {
        return new TransferStatusChanged(transferId, status, "ANSWER-1", Instant.parse("2026-11-17T08:30:05Z"));
    }
}
