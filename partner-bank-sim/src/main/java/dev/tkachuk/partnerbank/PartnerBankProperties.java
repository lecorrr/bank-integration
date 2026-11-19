package dev.tkachuk.partnerbank;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Test accounts: closed ones are refused with AC04, silent ones never get an answer. */
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
