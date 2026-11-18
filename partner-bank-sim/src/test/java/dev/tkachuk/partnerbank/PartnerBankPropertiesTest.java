package dev.tkachuk.partnerbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartnerBankPropertiesTest {

    @Test
    void refusesPaymentsToItsClosedAccountsOnly() {
        PartnerBankProperties properties = new PartnerBankProperties(List.of(TestPayments.CLOSED_ACCOUNT));

        assertThat(properties.isClosed(TestPayments.CLOSED_ACCOUNT)).isTrue();
        assertThat(properties.isClosed(TestPayments.OPEN_ACCOUNT)).isFalse();
    }

    @Test
    void noListMeansEveryAccountIsOpen() {
        assertThat(new PartnerBankProperties(null).isClosed(TestPayments.CLOSED_ACCOUNT)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UA853888880000000000000000999",
            "ua843888880000000000000000999",
            "UA84 3888 8800 0000 0000 0000 0099 9",
            "UA84",
            ""})
    void refusesAnythingButAnIbanInCapitalsWithoutSpaces(String iban) {
        assertThatThrownBy(() -> new PartnerBankProperties(List.of(TestPayments.OPEN_ACCOUNT, iban)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partner-bank.closed-accounts[1]");
    }

    @Test
    void refusesAnEmptyLineInTheList() {
        assertThatThrownBy(() -> new PartnerBankProperties(Arrays.asList((String) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partner-bank.closed-accounts[0]");
    }
}
