package dev.tkachuk.partnerbank;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SentPaymentsTest {

    private static final Instant NOW = Instant.parse("2026-11-18T09:15:04Z");

    private final SentPayments sent = new SentPayments();

    @Test
    void remembersAPaymentAndCountsEveryTimeItWentOut() {
        Pacs008 payment = TestPayments.toBankCore(UUID.randomUUID().toString());

        sent.remember(payment);
        assertThat(sent.find(payment.uetr()).orElseThrow().timesSent()).isEqualTo(0);
        sent.countSent(payment.uetr());
        sent.countSent(payment.uetr());

        SentPayments.Sent found = sent.find(payment.uetr()).orElseThrow();
        assertThat(found.payment()).isEqualTo(payment);
        assertThat(found.timesSent()).isEqualTo(2);
        assertThat(found.answers()).isEmpty();
    }

    @Test
    void keepsEveryAnswerAboutAPaymentOldestFirst() {
        Pacs008 payment = TestPayments.toBankCore(UUID.randomUUID().toString());
        sent.remember(payment);
        Pacs002 pending = Pacs002.answering(payment, TransactionStatus.PDNG, "ANSWER-1", NOW);
        Pacs002 accepted = Pacs002.answering(payment, TransactionStatus.ACSC, "ANSWER-2", NOW.plusSeconds(1));

        sent.recordAnswer(pending);
        SentPayments.Sent answered = sent.recordAnswer(accepted).orElseThrow();

        assertThat(answered.answers()).containsExactly(pending, accepted);
        assertThat(sent.find(payment.uetr()).orElseThrow().answers()).containsExactly(pending, accepted);
    }

    @Test
    void anAnswerAboutAPaymentItDoesNotRememberIsNotKept() {
        Pacs008 neverSent = TestPayments.toBankCore(UUID.randomUUID().toString());

        assertThat(sent.recordAnswer(Pacs002.rejecting(neverSent, StatusReason.NOT_SPECIFIED, "ANSWER-1", NOW)))
                .isEmpty();
        assertThat(sent.find(neverSent.uetr())).isEmpty();
    }

    @Test
    void forgetsTheOldestPaymentFirst() {
        SentPayments small = new SentPayments(2);
        Pacs008 oldest = TestPayments.toBankCore(UUID.randomUUID().toString());
        Pacs008 middle = TestPayments.toBankCore(UUID.randomUUID().toString());
        Pacs008 newest = TestPayments.toBankCore(UUID.randomUUID().toString());

        small.remember(oldest);
        small.remember(middle);
        small.remember(newest);

        assertThat(small.find(oldest.uetr())).isEmpty();
        assertThat(small.find(middle.uetr())).isPresent();
        assertThat(small.find(newest.uetr())).isPresent();
    }

    @Test
    void aSnapshotDoesNotChangeWithLaterAnswers() {
        Pacs008 payment = TestPayments.toBankCore(UUID.randomUUID().toString());
        sent.remember(payment);
        SentPayments.Sent before = sent.find(payment.uetr()).orElseThrow();

        sent.recordAnswer(Pacs002.answering(payment, TransactionStatus.ACSC, "ANSWER-1", NOW));

        assertThat(before.answers()).isEmpty();
    }
}
