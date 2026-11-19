package dev.tkachuk.partnerbank;

import java.util.regex.Pattern;

/** The IBAN rules the simulator's settings are checked against. */
final class Ibans {

    /** IBAN2007Identifier, the pacs.008 schema's pattern, without the lower case it tolerates. */
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}");

    private Ibans() {
    }

    /** In capitals without spaces, as a pacs.008 carries it, and with valid check digits. */
    static boolean isValid(String iban) {
        return iban != null && IBAN.matcher(iban).matches() && hasValidCheckDigits(iban);
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
