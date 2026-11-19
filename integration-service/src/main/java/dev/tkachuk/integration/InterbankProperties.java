package dev.tkachuk.integration;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Our bank and the other SEP participants, keyed by the six-digit bank code in their IBANs. */
@ConfigurationProperties("interbank")
public record InterbankProperties(String bankCode, String bic, Map<String, String> participants) {

    public static final String SETTLEMENT_CURRENCY = "UAH";

    private static final Pattern UKRAINIAN_IBAN = Pattern.compile("UA[0-9]{8}[0-9A-Z]{19}");
    private static final Pattern BANK_CODE = Pattern.compile("[0-9]{6}");
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

    public Optional<String> participantBic(String code) {
        return Optional.ofNullable(participants.get(code));
    }

    public boolean isOurAccount(String iban) {
        return bankCodeOf(iban).filter(bankCode::equals).isPresent();
    }

    public boolean isParticipantAccount(String iban, String participantBic) {
        return bankCodeOf(iban).flatMap(this::participantBic).filter(participantBic::equals).isPresent();
    }

    /** The bank code (MFO) is characters 5 to 10 of a Ukrainian IBAN. */
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
