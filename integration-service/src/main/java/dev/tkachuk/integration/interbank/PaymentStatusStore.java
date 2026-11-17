package dev.tkachuk.integration.interbank;

import dev.tkachuk.interbank.iso20022.Pacs002;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Remembers the sent pacs.008 messages and the status their pacs.002 reported. Kept in memory for the walking
 * skeleton; step 3 moves the status into bank-core's transfer, so this class goes away then.
 */
@Component
public class PaymentStatusStore {

    /** No answer yet. Answered payments show the ISO 20022 transaction status instead (ACSC, RJCT, PDNG). */
    public static final String SENT = "SENT";

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusStore.class);

    private final int capacity;
    private final Map<String, String> statuses;

    public PaymentStatusStore() {
        this(1_000);
    }

    /** @param capacity the most payments kept; the oldest is forgotten first */
    PaymentStatusStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
        this.statuses = new LinkedHashMap<>();
    }

    public synchronized void sent(String messageId) {
        statuses.put(messageId, SENT);
        if (statuses.size() > capacity) {
            String eldest = statuses.keySet().iterator().next();
            statuses.remove(eldest);
        }
    }

    /** @return false when the report answers a payment this store does not know, which is logged as a warning */
    public synchronized boolean record(Pacs002 report) {
        String messageId = report.originalMessageId();
        if (!statuses.containsKey(messageId)) {
            log.warn("pacs.002 {} answers pacs.008 {}, which this service did not send or no longer remembers",
                    report.messageId(), messageId);
            return false;
        }
        statuses.put(messageId, report.status().name());
        log.info("pacs.008 {} is {} (pacs.002 {})", messageId, report.status(), report.messageId());
        return true;
    }

    /** For a payment that was never sent after all. */
    public synchronized void forget(String messageId) {
        statuses.remove(messageId);
    }

    public synchronized Optional<String> statusOf(String messageId) {
        return Optional.ofNullable(statuses.get(messageId));
    }
}
