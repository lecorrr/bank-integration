package dev.tkachuk.integration.outgoing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransferRequestedTest {

    @Test
    void shouldAcceptValidTransfer() {
        assertThat(TestTransfers.transfer().transferId()).isEqualTo(TestTransfers.TRANSFER_ID);
    }

    static Stream<Arguments> brokenEvents() {
        return Stream.of(
                broken("no transfer id", event -> event.transferId(null), "transferId"),
                broken("a time-based UUID", event -> event.transferId(UUID.fromString(
                        "c232ab00-9414-11ec-b3c8-9f6bdeced846")), "transferId must be a random UUID (version 4)"),
                broken("an IBAN with spaces", event -> event.fromIban("UA84 3999 9900 0000 0000 0000 0000 1"),
                        "fromIban"),
                broken("a German IBAN", event -> event.toIban("DE89370400440532013000"), "toIban"),
                broken("a blank name", event -> event.toName("  "), "toName"),
                broken("a 141-character description", event -> event.description("x".repeat(141)), "description"),
                broken("a zero amount", event -> event.amount(BigDecimal.ZERO), "amount"),
                broken("a lower-case currency", event -> event.currency("uah"), "currency"),
                broken("no time", event -> event.requestedAt(null), "requestedAt"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenEvents")
    void shouldRejectInvalidTransfer(String event, UnaryOperator<TestTransfers.Builder> breakIt, String rule) {
        assertThatThrownBy(() -> breakIt.apply(TestTransfers.builder()).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(rule);
    }

    private static Arguments broken(String event, UnaryOperator<TestTransfers.Builder> breakIt, String rule) {
        return Arguments.of(event, breakIt, rule);
    }
}
