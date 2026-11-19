package dev.tkachuk.partnerbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SendingPropertiesTest {

    @Test
    void takesTheSettingsOfApplicationYml() {
        SendingProperties sending = new SendingProperties("PRTBUAUKXXX", TestPayments.OPEN_ACCOUNT,
                "Olena Kovalenko", "BNKCUAUKXXX");

        assertThat(sending.customerIban()).isEqualTo(TestPayments.OPEN_ACCOUNT);
    }

    static Stream<Arguments> wrongSettings() {
        return Stream.of(
                Arguments.of("a short BIC", "PRTB", TestPayments.OPEN_ACCOUNT, "Olena Kovalenko", "BNKCUAUKXXX",
                        "partner-bank.sending.bic"),
                Arguments.of("an IBAN with wrong check digits", "PRTBUAUKXXX", "UA653888880000000000000000777",
                        "Olena Kovalenko", "BNKCUAUKXXX", "partner-bank.sending.customer-iban"),
                Arguments.of("no customer name", "PRTBUAUKXXX", TestPayments.OPEN_ACCOUNT, " ", "BNKCUAUKXXX",
                        "partner-bank.sending.customer-name"),
                Arguments.of("no payee bank", "PRTBUAUKXXX", TestPayments.OPEN_ACCOUNT, "Olena Kovalenko", null,
                        "partner-bank.sending.payee-bank-bic"),
                Arguments.of("this bank as the payee bank", "PRTBUAUKXXX", TestPayments.OPEN_ACCOUNT,
                        "Olena Kovalenko", "PRTBUAUKXXX", "this bank's own BIC"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongSettings")
    void refusesAWrongSettingAndNamesIt(String setting, String bic, String customerIban, String customerName,
            String payeeBankBic, String named) {
        assertThatThrownBy(() -> new SendingProperties(bic, customerIban, customerName, payeeBankBic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(named);
    }
}
