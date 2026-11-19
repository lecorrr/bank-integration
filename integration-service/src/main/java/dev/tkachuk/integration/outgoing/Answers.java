package dev.tkachuk.integration.outgoing;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.StatusReason;

/** Decides what a pacs.002 does to a payment, given its current state. AnswersTest covers every rule. */
final class Answers {

    enum Action {
        SETTLE,
        REJECT,
        MARK_PENDING,
        RESEND,
        ASK_AGAIN,
        IGNORE,
        CONFLICT
    }

    private Answers() {
    }

    static Action decide(PaymentState state, Pacs002 report) {
        return state.isFinal() ? afterFinal(state, report) : beforeFinal(state, report);
    }

    private static Action beforeFinal(PaymentState state, Pacs002 report) {
        return switch (report.status()) {
            case ACSC -> Action.SETTLE;
            case PDNG -> state == PaymentState.PENDING ? Action.IGNORE : Action.MARK_PENDING;
            case RJCT -> onRefusal(report.reasonCode());
        };
    }

    // AG09 and AM05 don't refuse the payment itself: resend it, or ask what happened to it.
    private static Action onRefusal(String reasonCode) {
        if (StatusReason.PAYMENT_NOT_RECEIVED.equals(reasonCode)) {
            return Action.RESEND;
        }
        if (StatusReason.DUPLICATION.equals(reasonCode)) {
            return Action.ASK_AGAIN;
        }
        return Action.REJECT;
    }

    private static Action afterFinal(PaymentState state, Pacs002 report) {
        return switch (report.status()) {
            case ACSC -> state == PaymentState.SETTLED ? Action.IGNORE : Action.CONFLICT;
            case RJCT -> state == PaymentState.REJECTED || StatusReason.DUPLICATION.equals(report.reasonCode())
                    ? Action.IGNORE
                    : Action.CONFLICT;
            case PDNG -> Action.IGNORE;
        };
    }
}
