package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The payments the partner bank sent to bank-core's customers, and every answer bank-core's bank gave about each,
 * by UETR, which the answers repeat. Like StatusReports, the memory is the simulator's own: it keeps the latest
 * payments only, and a restart empties it.
 */
@Component
class SentPayments {

    /** How many payments the simulator remembers; the oldest are forgotten first. */
    static final int REMEMBERED_PAYMENTS = 10_000;

    /** By UETR, oldest first. Guarded by this. */
    private final Map<String, Tracked> payments;

    @Autowired
    SentPayments() {
        this(REMEMBERED_PAYMENTS);
    }

    /** With a memory of another size, for tests. */
    SentPayments(int rememberedPayments) {
        this.payments = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Tracked> eldest) {
                return size() > rememberedPayments;
            }
        };
    }

    /** A payment about to go out the first time, remembered first, so an answer that comes at once finds it. */
    synchronized void remember(Pacs008 payment) {
        payments.putIfAbsent(payment.uetr(), new Tracked(payment));
    }

    /** The payment went out, the first time or again. */
    synchronized void countSent(String uetr) {
        Tracked tracked = payments.get(uetr);
        if (tracked != null) {
            tracked.timesSent++;
        }
    }

    synchronized Optional<Sent> find(String uetr) {
        return Optional.ofNullable(payments.get(uetr)).map(Tracked::snapshot);
    }

    /** @return the payment the answer is about, with the answer; none for a payment the simulator does not remember */
    synchronized Optional<Sent> recordAnswer(Pacs002 answer) {
        Tracked tracked = payments.get(answer.originalUetr());
        if (tracked == null) {
            return Optional.empty();
        }
        tracked.answers.add(answer);
        return Optional.of(tracked.snapshot());
    }

    /** A payment as sent, how many times, and the answers about it so far, oldest first. */
    record Sent(Pacs008 payment, int timesSent, List<Pacs002> answers) {
    }

    /**
     * One payment and what came of it. Not named Entry: inside the map's anonymous subclass, that name is
     * Map.Entry.
     */
    private static final class Tracked {

        private final Pacs008 payment;
        private final List<Pacs002> answers = new ArrayList<>();
        private int timesSent;

        private Tracked(Pacs008 payment) {
            this.payment = payment;
        }

        private Sent snapshot() {
            return new Sent(payment, timesSent, List.copyOf(answers));
        }
    }
}
