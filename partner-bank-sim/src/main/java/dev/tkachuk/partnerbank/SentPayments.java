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

/** Payments this bank has sent and the answers they got, in memory for the REST API. */
@Component
class SentPayments {

    static final int REMEMBERED_PAYMENTS = 10_000;

    private final Map<String, Tracked> payments;

    @Autowired
    SentPayments() {
        this(REMEMBERED_PAYMENTS);
    }

    SentPayments(int rememberedPayments) {
        this.payments = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Tracked> eldest) {
                return size() > rememberedPayments;
            }
        };
    }

    synchronized void remember(Pacs008 payment) {
        payments.putIfAbsent(payment.uetr(), new Tracked(payment));
    }

    synchronized void countSent(String uetr) {
        Tracked tracked = payments.get(uetr);
        if (tracked != null) {
            tracked.timesSent++;
        }
    }

    synchronized Optional<Sent> find(String uetr) {
        return Optional.ofNullable(payments.get(uetr)).map(Tracked::snapshot);
    }

    synchronized Optional<Sent> recordAnswer(Pacs002 answer) {
        Tracked tracked = payments.get(answer.originalUetr());
        if (tracked == null) {
            return Optional.empty();
        }
        tracked.answers.add(answer);
        return Optional.of(tracked.snapshot());
    }

    record Sent(Pacs008 payment, int timesSent, List<Pacs002> answers) {
    }

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
