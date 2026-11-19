package dev.tkachuk.integration.incoming;

import dev.tkachuk.integration.InterbankProperties;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.StatusReason;
import java.util.Optional;

/**
 * Whether our bank takes a payment from another bank before bank-core sees it: what the pacs.008 and the directory
 * of participants alone can tell. Whether the payee's account exists, and is kept in the payment's currency, is
 * bank-core's question. No I/O, so every rule has a unit test.
 */
final class IncomingChecks {

    /** Hryvnia have 100 kopiyky. */
    private static final int KOPIYKY_DIGITS = 2;

    private IncomingChecks() {
    }

    /** Our refusal of the payment, if we refuse it; the first rule it breaks decides the reason. */
    static Optional<Refusal> refusalOf(Pacs008 payment, InterbankProperties bank) {
        if (!bank.bic().equals(payment.creditorAgentBic())) {
            return refuse(StatusReason.INCORRECT_AGENT, "it is for bank " + payment.creditorAgentBic() + ", not ours");
        }
        if (!bank.isParticipantAccount(payment.debtorIban(), payment.debtorAgentBic())) {
            return refuse(StatusReason.INCORRECT_AGENT, "the payer's bank " + payment.debtorAgentBic()
                    + " is not in the directory of participants, or does not keep the payer's account");
        }
        if (!InterbankProperties.SETTLEMENT_CURRENCY.equals(payment.currency())) {
            return refuse(StatusReason.NOT_ALLOWED_CURRENCY, "it is in " + payment.currency() + ", and SEP settles "
                    + InterbankProperties.SETTLEMENT_CURRENCY + " only");
        }
        if (payment.amount().stripTrailingZeros().scale() > KOPIYKY_DIGITS) {
            return refuse(StatusReason.INVALID_AMOUNT, "the amount has more than two decimals");
        }
        if (!bank.isOurAccount(payment.creditorIban())) {
            return refuse(StatusReason.INCORRECT_ACCOUNT_NUMBER, "the payee's IBAN is not an account at our bank");
        }
        return Optional.empty();
    }

    private static Optional<Refusal> refuse(String reasonCode, String reason) {
        return Optional.of(new Refusal(reasonCode, reason));
    }

    /**
     * @param reasonCode the ISO reason code the pacs.002 carries
     * @param reason     the same in words, for the log; it names banks but never the payer or the payee
     */
    record Refusal(String reasonCode, String reason) {
    }
}
