package dev.tkachuk.partnerbank;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** partner-bank.sending.*: this bank's BIC, its test customer, and the bank it pays. */
@ConfigurationProperties("partner-bank.sending")
record SendingProperties(String bic, String customerIban, String customerName, String payeeBankBic) {

    private static final Pattern BIC = Pattern.compile("[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final int MAX_NAME = 140;

    SendingProperties {
        requireBic("partner-bank.sending.bic", bic);
        if (!Ibans.isValid(customerIban)) {
            throw new IllegalArgumentException("partner-bank.sending.customer-iban must be an IBAN in capitals "
                    + "without spaces, with valid check digits");
        }
        if (customerName == null || customerName.isBlank() || customerName.length() > MAX_NAME) {
            throw new IllegalArgumentException("partner-bank.sending.customer-name must be 1 to 140 characters");
        }
        requireBic("partner-bank.sending.payee-bank-bic", payeeBankBic);
        if (bic.equals(payeeBankBic)) {
            throw new IllegalArgumentException("partner-bank.sending.payee-bank-bic is this bank's own BIC");
        }
    }

    private static void requireBic(String property, String value) {
        if (value == null || !BIC.matcher(value).matches()) {
            throw new IllegalArgumentException(property + " must be a BIC such as PRTBUAUKXXX, got \"" + value + "\"");
        }
    }
}
