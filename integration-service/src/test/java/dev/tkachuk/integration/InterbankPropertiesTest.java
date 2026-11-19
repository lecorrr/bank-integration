package dev.tkachuk.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InterbankPropertiesTest {

    private static final Map<String, String> PARTNER = Map.of("388888", "PRTBUAUKXXX");

    @Test
    void findsAParticipantsBicByItsBankCode() {
        InterbankProperties bank = new InterbankProperties("399999", "BNKCUAUKXXX", PARTNER);

        assertThat(bank.participantBic("388888")).hasValue("PRTBUAUKXXX");
        assertThat(bank.participantBic("300001")).isEmpty();
    }

    @Test
    void knowsItsOwnAccountsByTheBankCodeInTheIban() {
        InterbankProperties bank = new InterbankProperties("399999", "BNKCUAUKXXX", PARTNER);

        assertThat(bank.isOurAccount("UA843999990000000000000000001")).isTrue();
        assertThat(bank.isOurAccount("UA643888880000000000000000777")).isFalse();
        assertThat(bank.isOurAccount("DE89370400440532013000")).isFalse();
        assertThat(bank.isOurAccount(null)).isFalse();
    }

    @Test
    void knowsWhichParticipantKeepsAnAccount() {
        InterbankProperties bank = new InterbankProperties("399999", "BNKCUAUKXXX", PARTNER);

        assertThat(bank.isParticipantAccount("UA643888880000000000000000777", "PRTBUAUKXXX")).isTrue();
        assertThat(bank.isParticipantAccount("UA643888880000000000000000777", "OTHRUAUKXXX")).isFalse();
        assertThat(bank.isParticipantAccount("UA483777770000000000000000123", "PRTBUAUKXXX")).isFalse();
        assertThat(bank.isParticipantAccount("UA843999990000000000000000001", "BNKCUAUKXXX")).isFalse();
    }

    @Test
    void readsTheBankCodeOfAUkrainianIbanOnly() {
        assertThat(InterbankProperties.bankCodeOf("UA843999990000000000000000001")).hasValue("399999");
        assertThat(InterbankProperties.bankCodeOf("UA84399999000000000000000000")).as("one character short")
                .isEmpty();
        assertThat(InterbankProperties.bankCodeOf("DE89370400440532013000")).isEmpty();
    }

    static Stream<Arguments> wrongSettings() {
        return Stream.of(
                Arguments.of("a five-digit bank code", "39999", "BNKCUAUKXXX", PARTNER, "interbank.bank-code"),
                Arguments.of("a short BIC", "399999", "BNKC", PARTNER, "interbank.bic"),
                Arguments.of("no participants", "399999", "BNKCUAUKXXX", Map.of(), "at least one other bank"),
                Arguments.of("a participant's five-digit code", "399999", "BNKCUAUKXXX",
                        Map.of("38888", "PRTBUAUKXXX"), "Every key of interbank.participants"),
                Arguments.of("a participant's lower-case BIC", "399999", "BNKCUAUKXXX",
                        Map.of("388888", "prtbuaukxxx"), "interbank.participants.388888"),
                Arguments.of("our own bank among the participants", "399999", "BNKCUAUKXXX",
                        Map.of("399999", "BNKCUAUKXXX"), "our own bank code"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongSettings")
    void refusesAWrongSettingAndNamesIt(String setting, String bankCode, String bic, Map<String, String> participants,
            String named) {
        assertThatThrownBy(() -> new InterbankProperties(bankCode, bic, participants))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(named);
    }
}
