package dev.tkachuk.integration.outgoing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.integration.TestDatabase;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL of OutgoingPayments against a real PostgreSQL (TestDatabase). No Spring context: the repository and its
 * table are all that is under test.
 */
class OutgoingPaymentsIT {

    private static final Instant NOW = Instant.parse("2026-11-17T08:30:00Z");
    private static final Instant LATER = NOW.plusSeconds(30);

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final OutgoingPayments payments = new OutgoingPayments(jdbc);

    @BeforeEach
    void startWithAnEmptyTable() {
        jdbc.sql("DELETE FROM outgoing_payment").update();
    }

    @Test
    void storesAPaymentOnceAndAfterThatReturnsTheOneStored() {
        UUID transferId = UUID.randomUUID();

        OutgoingPayment first = payments.insertIfAbsent(transferId, idOf(transferId), "<first/>", NOW);
        OutgoingPayment again = payments.insertIfAbsent(transferId, idOf(transferId), "<second/>", LATER);

        assertThat(first.state()).isEqualTo(PaymentState.NEW);
        assertThat(again).isEqualTo(first);
        assertThat(again.pacs008()).isEqualTo("<first/>");
        assertThat(payments.findByMessageId(idOf(transferId))).contains(first);
    }

    @Test
    void aSendStartsTheStatusRequestsOver() {
        UUID transferId = sent(NOW);
        claimAndAsk(transferId);

        payments.recordSent(transferId, LATER, LATER.plusSeconds(30));

        OutgoingPayment payment = payments.find(transferId).orElseThrow();
        assertThat(payment.state()).isEqualTo(PaymentState.SENT);
        assertThat(payment.timesSent()).isEqualTo(2);
        assertThat(payment.statusRequests()).isZero();
        assertThat(payment.sentAt()).isEqualTo(LATER);
        assertThat(payment.nextCheckAt()).isEqualTo(LATER.plusSeconds(30));
    }

    @Test
    void aFinalAnswerIsRecordedOnceAndStands() {
        UUID transferId = sent(NOW);

        boolean settled = payments.recordFinal(transferId, PaymentState.SETTLED, report(TransactionStatus.ACSC, null),
                LATER);
        boolean rejected = payments.recordFinal(transferId, PaymentState.REJECTED,
                report(TransactionStatus.RJCT, StatusReason.CLOSED_ACCOUNT), LATER);
        payments.recordSent(transferId, LATER, LATER);
        payments.recordPending(transferId);

        assertThat(settled).isTrue();
        assertThat(rejected).isFalse();
        OutgoingPayment payment = payments.find(transferId).orElseThrow();
        assertThat(payment.state()).isEqualTo(PaymentState.SETTLED);
        assertThat(payment.reportId()).isEqualTo("REPORT-ACSC");
        assertThat(payment.answeredAt()).isEqualTo(LATER);
        assertThat(payment.nextCheckAt()).isNull();
        assertThat(payment.timesSent()).isEqualTo(1);
    }

    @Test
    void keepsOurOwnRefusalAsAFinalRejectionWithoutAPacs008() {
        UUID transferId = UUID.randomUUID();

        payments.recordRefusal(transferId, idOf(transferId), "REFUSAL-1", NOW);
        payments.insertIfAbsent(transferId, idOf(transferId), "<pacs008/>", LATER);

        OutgoingPayment refused = payments.find(transferId).orElseThrow();
        assertThat(refused.state()).isEqualTo(PaymentState.REJECTED);
        assertThat(refused.pacs008()).isNull();
        assertThat(refused.reportId()).isEqualTo("REFUSAL-1");
        assertThat(refused.answeredAt()).isEqualTo(NOW);
        assertThat(refused.nextCheckAt()).isNull();
    }

    @Test
    void keepsTheReasonOfARefusal() {
        UUID transferId = sent(NOW);

        payments.recordFinal(transferId, PaymentState.REJECTED,
                report(TransactionStatus.RJCT, StatusReason.CLOSED_ACCOUNT), LATER);

        assertThat(payments.find(transferId).orElseThrow().reasonCode()).isEqualTo(StatusReason.CLOSED_ACCOUNT);
    }

    @Test
    void claimsTheOpenPaymentsWhoseCheckIsDueAndHoldsThemBackUntilTheLeaseEnds() {
        UUID due = sent(NOW.minusSeconds(60));
        UUID duePending = sent(NOW.minusSeconds(45));
        payments.recordPending(duePending);
        UUID notDue = sent(LATER.plusSeconds(1));
        UUID settled = sent(NOW.minusSeconds(60));
        payments.recordFinal(settled, PaymentState.SETTLED, report(TransactionStatus.ACSC, null), NOW);
        Instant leaseUntil = LATER.plus(Duration.ofMinutes(1));

        List<OutgoingPayment> claimed = payments.claimDue(LATER, leaseUntil, 10);

        assertThat(claimed).extracting(OutgoingPayment::transferId).containsExactlyInAnyOrder(due, duePending);
        assertThat(claimed).allSatisfy(payment -> assertThat(payment.nextCheckAt()).isEqualTo(leaseUntil));
        assertThat(payments.claimDue(LATER, leaseUntil, 10)).as("claimed again before the lease ends").isEmpty();
        assertThat(payments.claimDue(leaseUntil, leaseUntil.plusSeconds(60), 10))
                .extracting(OutgoingPayment::transferId)
                .containsExactlyInAnyOrder(due, duePending, notDue);
    }

    @Test
    void claimsNoMoreThanTheLimitTheLongestDueFirst() {
        UUID oldest = sent(NOW.minusSeconds(90));
        UUID middle = sent(NOW.minusSeconds(60));
        sent(NOW.minusSeconds(30));

        List<OutgoingPayment> claimed = payments.claimDue(LATER, LATER.plusSeconds(60), 2);

        assertThat(claimed).extracting(OutgoingPayment::transferId).containsExactlyInAnyOrder(oldest, middle);
    }

    @Test
    void aClaimSkipsThePaymentsAnotherInstanceIsClaimingInsteadOfWaitingForThem() throws Exception {
        for (int i = 1; i <= 4; i++) {
            sent(NOW.minusSeconds(60 + i));
        }
        try (Connection otherInstance = TestDatabase.connect()) {
            otherInstance.setAutoCommit(false);
            // Its claim is still running: the two payments due longest stay locked until it commits.
            try (PreparedStatement claim = otherInstance.prepareStatement(
                    "SELECT transfer_id FROM outgoing_payment ORDER BY next_check_at LIMIT 2 FOR UPDATE")) {
                claim.executeQuery().close();
            }

            List<OutgoingPayment> claimed = payments.claimDue(LATER, LATER.plusSeconds(60), 10);

            assertThat(claimed).as("the payments nobody else holds, without waiting").hasSize(2);
            otherInstance.rollback();
        }
    }

    @Test
    void countsAStatusRequestUnlessThePaymentWentOutAgainMeanwhile() {
        UUID transferId = sent(NOW);
        OutgoingPayment claimed = payments.claimDue(LATER, LATER.plusSeconds(60), 10).getFirst();

        payments.recordStatusRequest(transferId, claimed.timesSent(), LATER.plusSeconds(60));
        assertThat(payments.find(transferId).orElseThrow().statusRequests()).isEqualTo(1);

        payments.recordSent(transferId, LATER, LATER.plusSeconds(30));
        payments.recordStatusRequest(transferId, claimed.timesSent(), LATER.plusSeconds(120));
        OutgoingPayment payment = payments.find(transferId).orElseThrow();
        assertThat(payment.statusRequests()).isZero();
        assertThat(payment.nextCheckAt()).isEqualTo(LATER.plusSeconds(30));
    }

    @Test
    void asksAboutAPaymentAheadOfItsScheduleWhenTold() {
        UUID transferId = sent(NOW);
        payments.recordSent(transferId, NOW, NOW.plus(Duration.ofHours(1)));
        assertThat(payments.claimDue(LATER, LATER, 10)).as("claimed before it was told").isEmpty();

        payments.askNow(transferId, LATER);

        assertThat(payments.claimDue(LATER, LATER.plusSeconds(60), 10)).extracting(OutgoingPayment::transferId)
                .containsExactly(transferId);
    }

    /** A payment whose pacs.008 went out at sentAt, due its first status request then. */
    private UUID sent(Instant sentAt) {
        UUID transferId = UUID.randomUUID();
        payments.insertIfAbsent(transferId, idOf(transferId), "<pacs008/>", sentAt);
        payments.recordSent(transferId, sentAt, sentAt);
        return transferId;
    }

    private void claimAndAsk(UUID transferId) {
        OutgoingPayment claimed = payments.claimDue(LATER, LATER.plusSeconds(60), 10).getFirst();
        payments.recordStatusRequest(transferId, claimed.timesSent(), LATER.plusSeconds(60));
    }

    private static Pacs002 report(TransactionStatus status, String reasonCode) {
        return new Pacs002("REPORT-" + status, LATER, "ORIGINAL-1", "NOTPROVIDED", "ORIGINAL-1",
                UUID.randomUUID().toString(), status, reasonCode);
    }

    private static String idOf(UUID transferId) {
        return transferId.toString().replace("-", "");
    }
}
