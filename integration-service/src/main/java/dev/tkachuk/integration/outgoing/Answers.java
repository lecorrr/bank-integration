package dev.tkachuk.integration.outgoing;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.StatusReason;

/**
 * What one answer from the other bank does to the payment it is about. No I/O, so every rule has a unit test.
 *
 * <p>The rule behind the others: only a definite answer from the other bank moves the money. Silence, "not
 * received" and "duplicate" never release it, because the payment may still be settled over there, and giving
 * the money back to the payer as well would pay it twice. Such a payment is sent again or asked about instead,
 * for as long as it takes; AnswerRoute and StatusRequestRoute do that.
 */
final class Answers {

    /** What AnswerRoute does with the answer. */
    enum Action {
        /** ACSC: tell bank-core SETTLED, and it completes the transfer. */
        SETTLE,
        /** RJCT: tell bank-core REJECTED, and it gives the money back. */
        REJECT,
        /** The first PDNG: tell bank-core the other bank has the payment, and keep asking. */
        MARK_PENDING,
        /** RJCT AG09: the other bank never received the payment. Send the same pacs.008 again. */
        RESEND,
        /** RJCT AM05: the other bank took a copy for a duplicate. Ask for the answer to the payment itself. */
        ASK_AGAIN,
        /** Nothing changes: the final answer again, a PDNG with nothing new in it, or a late duplicate refusal. */
        IGNORE,
        /** It contradicts the final answer already acted on. A person has to look. */
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

    /** RJCT refuses the payment, unless its reason says the refusal is about a copy that went astray or twice. */
    private static Action onRefusal(String reasonCode) {
        if (StatusReason.PAYMENT_NOT_RECEIVED.equals(reasonCode)) {
            return Action.RESEND;
        }
        if (StatusReason.DUPLICATION.equals(reasonCode)) {
            return Action.ASK_AGAIN;
        }
        return Action.REJECT;
    }

    /**
     * A final answer stands: the same one again changes nothing, the opposite one is a conflict. A copy refused as
     * a duplicate is no conflict: it is what a payment sent again gets once the first one has been answered.
     */
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
