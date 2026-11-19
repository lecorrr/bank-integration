package dev.tkachuk.integration;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Our bank, and the banks we can pay through the NBU's payment system (SEP). A Ukrainian IBAN names its bank by
 * the six-digit code after the check digits (UA84 399999 ...); ISO 20022 messages name banks by BIC. The NBU
 * publishes the full directory of participants; this project knows one, the partner bank. Checked when the service
 * starts, so a typo stops it there instead of at the first payment.
 *
 * @param bankCode     the code in our own IBANs
 * @param bic          our BIC: the debtor agent of every payment we send, the creditor agent of every one we take
 * @param participants every other bank we exchange payments with: its code, and its BIC
 */
@ConfigurationProperties("interbank")
public record InterbankProperties(String bankCode, String bic, Map<String, String> participants) {

    /** The NBU's payment system settles hryvnia only. */
    public static final String SETTLEMENT_CURRENCY = "UAH";

    /** UA, two check digits, the six-digit bank code, then the 19-character account number. */
    private static final Pattern UKRAINIAN_IBAN = Pattern.compile("UA[0-9]{8}[0-9A-Z]{19}");
    private static final Pattern BANK_CODE = Pattern.compile("[0-9]{6}");
    /** BICFIDec2014Identifier, as the pacs.008 schema defines it. */
    private static final Pattern BIC = Pattern.compile("[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");

    public InterbankProperties {
        requireMatch("interbank.bank-code", bankCode, BANK_CODE, "six digits");
        requireMatch("interbank.bic", bic, BIC, "a BIC such as BNKCUAUKXXX");
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("interbank.participants must list at least one other bank");
        }
        participants.forEach((code, participantBic) -> {
            requireMatch("Every key of interbank.participants", code, BANK_CODE, "six digits");
            requireMatch("interbank.participants." + code, participantBic, BIC, "a BIC such as PRTBUAUKXXX");
        });
        if (participants.containsKey(bankCode)) {
            throw new IllegalArgumentException("interbank.participants lists our own bank code " + bankCode);
        }
        participants = Map.copyOf(participants);
    }

    /** The BIC of the bank with this code, if we can pay it. */
    public Optional<String> participantBic(String code) {
        return Optional.ofNullable(participants.get(code));
    }

    /** Whether the account is one of our bank's. */
    public boolean isOurAccount(String iban) {
        return bankCodeOf(iban).filter(bankCode::equals).isPresent();
    }

    /** Whether the account is at the participant with this BIC: the payer's account at the payer's bank. */
    public boolean isParticipantAccount(String iban, String participantBic) {
        return bankCodeOf(iban).flatMap(this::participantBic).filter(participantBic::equals).isPresent();
    }

    /** The code of the bank that keeps the account; none for an IBAN that is not Ukrainian. */
    public static Optional<String> bankCodeOf(String iban) {
        if (iban == null || !UKRAINIAN_IBAN.matcher(iban).matches()) {
            return Optional.empty();
        }
        return Optional.of(iban.substring(4, 10));
    }

    private static void requireMatch(String property, String value, Pattern rule, String expected) {
        if (value == null || !rule.matcher(value).matches()) {
            throw new IllegalArgumentException(property + " must be " + expected + ", got \"" + value + "\"");
        }
    }
}
