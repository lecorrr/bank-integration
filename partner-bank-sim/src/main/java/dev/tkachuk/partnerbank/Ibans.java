package dev.tkachuk.partnerbank;

import java.util.regex.Pattern;

/** IBAN format and check digits (ISO 13616, mod 97). */
final class Ibans {

    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}");

    private Ibans() {
    }

    static boolean isValid(String iban) {
        return iban != null && IBAN.matcher(iban).matches() && hasValidCheckDigits(iban);
    }

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
