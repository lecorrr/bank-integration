package dev.tkachuk.partnerbank;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the simulated partner bank decides. A payment to one of its closed accounts is refused, the way a real bank
 * refuses a payment to an account it has closed. A payment to a silent account is received and never decided,
 * as if its answer were lost, so bank-integration has to ask for it. Every other valid payment is accepted.
 * Checked when the simulator starts, so a mistyped IBAN stops it there instead of silently never matching.
 *
 * @param closedAccounts IBANs in capitals without spaces, as a pacs.008 carries them
 * @param silentAccounts the same, for payments that get no answer
 */
@ConfigurationProperties("partner-bank")
record PartnerBankProperties(List<String> closedAccounts, List<String> silentAccounts) {

    PartnerBankProperties {
        closedAccounts = checked("partner-bank.closed-accounts", closedAccounts);
        silentAccounts = checked("partner-bank.silent-accounts", silentAccounts);
        for (int i = 0; i < silentAccounts.size(); i++) {
            if (closedAccounts.contains(silentAccounts.get(i))) {
                throw new IllegalArgumentException(
                        "partner-bank.silent-accounts[" + i + "] is in partner-bank.closed-accounts as well");
            }
        }
    }

    boolean isClosed(String iban) {
        return closedAccounts.contains(iban);
    }

    boolean isSilent(String iban) {
        return silentAccounts.contains(iban);
    }

    /** No list means no such accounts; every entry must be a valid IBAN. */
    private static List<String> checked(String property, List<String> ibans) {
        if (ibans == null) {
            return List.of();
        }
        for (int i = 0; i < ibans.size(); i++) {
            String iban = ibans.get(i);
            if (!Ibans.isValid(iban)) {
                throw new IllegalArgumentException(property + "[" + i
                        + "] must be an IBAN in capitals without spaces, with valid check digits");
            }
        }
        return List.copyOf(ibans);
    }
}
