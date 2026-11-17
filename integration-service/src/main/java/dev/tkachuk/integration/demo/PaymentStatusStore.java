package dev.tkachuk.integration.demo;

import dev.tkachuk.integration.outgoing.TransferStatusChanged;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What the demo knows of the transfers it asked for: REQUESTED once it has published one, then the status from
 * the status event. Kept in memory and bounded; step 3 replaces the demo with bank-core itself.
 */
@Component
class PaymentStatusStore {

    /** Published, no status event yet. Then SETTLED, REJECTED or PENDING, as the status event says. */
    static final String REQUESTED = "REQUESTED";

    private final int capacity;
    private final Map<String, String> statuses = new LinkedHashMap<>();

    PaymentStatusStore() {
        this(1_000);
    }

    /** @param capacity the most transfers kept; the oldest is forgotten first */
    PaymentStatusStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
    }

    synchronized void requested(String transferId) {
        put(transferId, REQUESTED);
    }

    /** Also for a transfer the demo did not ask for, such as a test's: the status topic carries every transfer. */
    synchronized void record(TransferStatusChanged event) {
        put(event.transferId().toString(), event.status().name());
    }

    /** For a transfer whose event was never published after all. */
    synchronized void forget(String transferId) {
        statuses.remove(transferId);
    }

    synchronized Optional<String> statusOf(String transferId) {
        return Optional.ofNullable(statuses.get(transferId));
    }

    private void put(String transferId, String status) {
        statuses.put(transferId, status);
        if (statuses.size() > capacity) {
            String eldest = statuses.keySet().iterator().next();
            statuses.remove(eldest);
        }
    }
}
