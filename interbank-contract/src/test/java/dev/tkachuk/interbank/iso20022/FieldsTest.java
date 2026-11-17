package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The rules a record checks when it is built, before any XML exists. */
class FieldsTest {

    static Stream<Arguments> valuesThatBreakTheirRule() {
        return Stream.of(
                broken("an IBAN with a space", () -> Fields.iban("debtorIban", "UA84 3999990000000000000000001"),
                        "debtorIban must be an IBAN", "3999990000000000000000001"),
                broken("a four-character BIC", () -> Fields.bic("creditorAgentBic", "PRTB"),
                        "creditorAgentBic must be a BIC", "PRTB"),
                broken("an upper-case UETR", () -> Fields.uetr("0F5BD7C2-3A4E-4C1B-9D0E-6F2A8B1C3D4E"),
                        "uetr must be a lower-case UUID version 4", "0F5BD7C2"),
                broken("a lower-case currency", () -> Fields.currency("uah"), "currency must be", "uah"),
                broken("a 36-character id", () -> Fields.max35("messageId", "M".repeat(36)),
                        "messageId must be 1 to 35 characters", "MMMM"),
                broken("a 141-character name", () -> Fields.max140("debtorName", "Olena ".repeat(24)),
                        "debtorName must be 1 to 140 characters", "Olena"),
                broken("no amount", () -> Fields.positiveAmount(null), "amount must be greater than zero", "null"),
                broken("no date", () -> Fields.required("settlementDate", null), "settlementDate is required",
                        "null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valuesThatBreakTheirRule")
    void refusesAValueThatBreaksItsRuleWithoutQuotingIt(String value, ThrowingCallable check, String rule,
            String part) {
        assertThatThrownBy(check)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(rule)
                .hasMessageNotContaining(part);
    }

    private static Arguments broken(String value, ThrowingCallable check, String rule, String part) {
        return Arguments.of(value, check, rule, part);
    }
}
