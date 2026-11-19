package dev.tkachuk.integration.incoming;

import dev.tkachuk.integration.InterbankProperties;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.StatusReason;
import java.util.Optional;

/** Checks our bank makes before a payment reaches bank-core. The first rule broken gives the reason code. */
final class IncomingChecks {

    private static final int KOPIYKY_DIGITS = 2;

    private IncomingChecks() {
    }

    static Optional<Refusal> refusalOf(Pacs008 payment, InterbankProperties bank) {
        if (!bank.bic().equals(payment.creditorAgentBic())) {
            return refuse(StatusReason.INCORRECT_AGENT,
                    "creditor agent " + payment.creditorAgentBic() + " is not our bank");
        }
        if (!bank.isParticipantAccount(payment.debtorIban(), payment.debtorAgentBic())) {
            return refuse(StatusReason.INCORRECT_AGENT, "debtor agent " + payment.debtorAgentBic()
                    + " is not a participant or does not hold the debtor's account");
        }
        if (!InterbankProperties.SETTLEMENT_CURRENCY.equals(payment.currency())) {
            return refuse(StatusReason.NOT_ALLOWED_CURRENCY, "currency " + payment.currency()
                    + " is not supported, SEP settles " + InterbankProperties.SETTLEMENT_CURRENCY + " only");
        }
        if (payment.amount().stripTrailingZeros().scale() > KOPIYKY_DIGITS) {
            return refuse(StatusReason.INVALID_AMOUNT, "amount has more than two decimals");
        }
        if (!bank.isOurAccount(payment.creditorIban())) {
            return refuse(StatusReason.INCORRECT_ACCOUNT_NUMBER, "creditor IBAN is not an account at our bank");
        }
        return Optional.empty();
    }

    private static Optional<Refusal> refuse(String reasonCode, String reason) {
        return Optional.of(new Refusal(reasonCode, reason));
    }

    record Refusal(String reasonCode, String reason) {
    }
}
