package dev.tkachuk.partnerbank;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the simulated partner bank decides. A payment to one of its closed accounts is refused, the way a real bank
 * refuses a payment to an account it has closed; every other valid payment is accepted. Checked when the
 * simulator starts, so a mistyped IBAN stops it there instead of silently never matching a payment.
 *
 * @param closedAccounts IBANs in capitals without spaces, as a pacs.008 carries them
 */
@ConfigurationProperties("partner-bank")
record PartnerBankProperties(List<String> closedAccounts) {

    /** IBAN2007Identifier, the pacs.008 schema's pattern, without the lower case it tolerates. */
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}");

    PartnerBankProperties {
        if (closedAccounts == null) {
            closedAccounts = List.of();
        }
        for (int i = 0; i < closedAccounts.size(); i++) {
            String iban = closedAccounts.get(i);
            if (iban == null || !IBAN.matcher(iban).matches() || !hasValidCheckDigits(iban)) {
                throw new IllegalArgumentException("partner-bank.closed-accounts[" + i
                        + "] must be an IBAN in capitals without spaces, with valid check digits");
            }
        }
        closedAccounts = List.copyOf(closedAccounts);
    }

    boolean isClosed(String iban) {
        return closedAccounts.contains(iban);
    }

    /** ISO 13616: moved to the end, its first four characters included, an IBAN is 1 modulo 97 (A = 10, Z = 35). */
    private static boolean hasValidCheckDigits(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            int value = Character.digit(rearranged.charAt(i), 36);
            remainder = ((value < 10 ? remainder * 10 : remainder * 100) + value) % 97;
        }
        return remainder == 1;
    }
}
