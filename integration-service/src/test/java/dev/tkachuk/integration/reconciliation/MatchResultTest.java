package dev.tkachuk.integration.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MatchResultTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "settled for the booked amount,      false, true,  true,  150.00, UAH, MATCHED",
            "the same amount with one decimal,   false, true,  true,  150.0,  UAH, MATCHED",
            "settled for another amount,         false, true,  true,  140.00, UAH, AMOUNT_MISMATCH",
            "settled in another currency,        false, true,  true,  150.00, USD, AMOUNT_MISMATCH",
            "still waiting or rejected,          false, true,  false, 150.00, UAH, NOT_SETTLED",
            "refused by us before a pacs.008,    false, true,  false,       ,    , NOT_SETTLED",
            "not a payment of ours,              false, false, false,       ,    , UNKNOWN",
            "booked before,                      true,  true,  true,  150.00, UAH, DUPLICATE",
            "booked before and not ours either,  true,  false, false,       ,    , DUPLICATE"})
    void shouldGiveEachLineItsResult(String line, boolean bookedBefore, boolean known, boolean settled,
            BigDecimal ourAmount, String ourCurrency, MatchResult expected) {
        StatementLine booked = new StatementLine(1, "PRTB0000000001", CreditDebit.DBIT, new BigDecimal("150.00"),
                UUID.randomUUID(), bookedBefore, known, settled, ourAmount, ourCurrency);

        assertThat(MatchResult.of(booked, "UAH")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"MATCHED, false", "AMOUNT_MISMATCH, true", "NOT_SETTLED, true", "UNKNOWN, true", "DUPLICATE, true",
            "MISSING, true"})
    void shouldCountEverythingButMatchAsBreak(MatchResult result, boolean isBreak) {
        assertThat(result.isBreak()).isEqualTo(isBreak);
        assertThat(Arrays.asList(MatchResult.values())).contains(result);
    }
}
