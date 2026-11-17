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
