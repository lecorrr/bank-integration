package dev.tkachuk.integration.incoming;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.integration.incoming.IncomingChecks.Refusal;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.StatusReason;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class IncomingChecksTest {

    @Test
    void shouldAcceptValidPayment() {
        assertThat(IncomingChecks.refusalOf(TestPayments.payment(), TestPayments.BANK)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"250", "250.5", "250.50", "250.500"})
    void shouldAcceptAmountInWholeKopiyky(String amount) {
        Pacs008 payment = TestPayments.builder().amount(amount).build();

        assertThat(IncomingChecks.refusalOf(payment, TestPayments.BANK)).isEmpty();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("for another bank",
                        TestPayments.builder().creditorAgentBic("PRTBUAUKXXX"), StatusReason.INCORRECT_AGENT),
                Arguments.of("from a bank that is not a participant",
                        TestPayments.builder().debtorAgentBic("UNKNUAUKXXX"), StatusReason.INCORRECT_AGENT),
                Arguments.of("from an account the payer's bank does not keep",
                        TestPayments.builder().debtorIban("UA483777770000000000000000123"),
                        StatusReason.INCORRECT_AGENT),
                Arguments.of("in dollars", TestPayments.builder().currency("USD"), StatusReason.NOT_ALLOWED_CURRENCY),
                Arguments.of("with a fraction of a kopiyka", TestPayments.builder().amount("250.005"),
                        StatusReason.INVALID_AMOUNT),
                Arguments.of("to an account at another bank",
                        TestPayments.builder().creditorIban("UA643888880000000000000000777"),
                        StatusReason.INCORRECT_ACCOUNT_NUMBER),
                Arguments.of("to an IBAN that is not Ukrainian",
                        TestPayments.builder().creditorIban("DE89370400440532013000"),
                        StatusReason.INCORRECT_ACCOUNT_NUMBER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void shouldRejectPaymentWithReasonOfBrokenRule(String payment, TestPayments.Builder builder,
            String reasonCode) {
        Optional<Refusal> refusal = IncomingChecks.refusalOf(builder.build(), TestPayments.BANK);

        assertThat(refusal).isPresent();
        assertThat(refusal.get().reasonCode()).isEqualTo(reasonCode);
        assertThat(refusal.get().reason()).isNotBlank();
    }

    @Test
    void shouldNotQuoteAccountsInReason() {
        Pacs008 payment = TestPayments.builder().creditorIban("UA643888880000000000000000777").build();

        String reason = IncomingChecks.refusalOf(payment, TestPayments.BANK).orElseThrow().reason();

        assertThat(reason).doesNotContain(payment.creditorIban()).doesNotContain(payment.debtorIban());
    }

    @Test
    void shouldUseFirstBrokenRule() {
        Pacs008 payment = TestPayments.builder().creditorAgentBic("PRTBUAUKXXX").currency("USD").build();

        assertThat(IncomingChecks.refusalOf(payment, TestPayments.BANK).orElseThrow().reasonCode())
                .isEqualTo(StatusReason.INCORRECT_AGENT);
    }
}
