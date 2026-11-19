package dev.tkachuk.integration.outgoing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.InterbankProperties;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Pacs008MapperTest {

    private static final InterbankProperties BANK =
            new InterbankProperties("399999", "BNKCUAUKXXX", Map.of("388888", "PRTBUAUKXXX"));
    private static final Instant NOW = Instant.parse("2026-11-17T08:30:00Z");

    private final Pacs008Mapper mapper = mapperAt(NOW);

    @Test
    void shouldCopyPartiesAmountAndDescription() {
        TransferRequested transfer = TestTransfers.transfer();

        Pacs008 payment = mapper.toPacs008(transfer);

        assertThat(payment.debtorName()).isEqualTo(transfer.fromName());
        assertThat(payment.debtorIban()).isEqualTo(transfer.fromIban());
        assertThat(payment.creditorName()).isEqualTo(transfer.toName());
        assertThat(payment.creditorIban()).isEqualTo(transfer.toIban());
        assertThat(payment.amount()).isEqualTo(new BigDecimal("150.00"));
        assertThat(payment.currency()).isEqualTo("UAH");
        assertThat(payment.remittanceInformation()).isEqualTo(transfer.description());
        assertThat(payment.createdAt()).isEqualTo(NOW);
    }

    @Test
    void shouldSetBothBanksByBic() {
        Pacs008 payment = mapper.toPacs008(TestTransfers.transfer());

        assertThat(payment.debtorAgentBic()).isEqualTo("BNKCUAUKXXX");
        assertThat(payment.creditorAgentBic()).isEqualTo("PRTBUAUKXXX");
    }

    @Test
    void shouldDeriveIdsFromTransferId() {
        Pacs008 payment = mapper.toPacs008(TestTransfers.transfer());

        assertThat(payment.uetr()).isEqualTo("0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e");
        assertThat(payment.messageId()).isEqualTo("0f5bd7c23a4e4c1b9d0e6f2a8b1c3d4e");
        assertThat(payment.transactionId()).isEqualTo(payment.messageId());
        assertThat(payment.endToEndId()).isEqualTo(Pacs008Mapper.NOT_PROVIDED);
    }

    @Test
    void shouldGiveSameIdsToRedeliveredTransfer() {
        TransferRequested transfer = TestTransfers.transfer();

        Pacs008 first = mapper.toPacs008(transfer);
        Pacs008 aMinuteLater = mapperAt(NOW.plusSeconds(60)).toPacs008(transfer);

        assertThat(aMinuteLater.messageId()).isEqualTo(first.messageId());
        assertThat(aMinuteLater.uetr()).isEqualTo(first.uetr());
    }

    @Test
    void shouldUseKyivBusinessDayAsSettlementDate() {
        Pacs008 payment = mapperAt(Instant.parse("2026-11-16T23:30:00Z")).toPacs008(TestTransfers.transfer());

        assertThat(payment.settlementDate()).isEqualTo(LocalDate.parse("2026-11-17"));
    }

    @Test
    void shouldWriteAmountWithTwoDecimals() {
        Pacs008 payment = mapper.toPacs008(TestTransfers.builder().amount(new BigDecimal("150")).build());

        assertThat(payment.amount()).isEqualTo(new BigDecimal("150.00"));
    }

    @Test
    void shouldBuildPacs008ThatPassesSchema() {
        Pacs008 payment = mapper.toPacs008(TestTransfers.transfer());

        assertThat(Pacs008.parse(payment.toXml())).isEqualTo(payment);
    }

    static Stream<Arguments> transfersOurBankCannotSend() {
        return Stream.of(
                refused("a payer at another bank", transfer -> transfer.fromIban("UA213888880000000000000000001"),
                        "fromIban belongs to bank 388888"),
                refused("a payee at our own bank", transfer -> transfer.toIban("UA213999990000000000000000002"),
                        "toIban is an account at our own bank"),
                refused("a payee at a bank outside the directory",
                        transfer -> transfer.toIban("UA213000010000000000000000003"), "bank 300001, which is not"),
                refused("another currency", transfer -> transfer.currency("EUR"), "SEP settles UAH only"),
                refused("a fraction of a kopiyka", transfer -> transfer.amount(new BigDecimal("150.005")),
                        "more than two decimals"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transfersOurBankCannotSend")
    void shouldRejectTransferThatCannotBeSent(String transfer, UnaryOperator<TestTransfers.Builder> change,
            String reason) {
        TransferRequested refused = change.apply(TestTransfers.builder()).build();

        assertThatThrownBy(() -> mapper.toPacs008(refused))
                .isInstanceOf(TransferRefusedException.class)
                .hasMessageContaining(TestTransfers.TRANSFER_ID.toString())
                .hasMessageContaining(reason);
    }

    private static Arguments refused(String transfer, UnaryOperator<TestTransfers.Builder> change, String reason) {
        return Arguments.of(transfer, change, reason);
    }

    private static Pacs008Mapper mapperAt(Instant now) {
        return new Pacs008Mapper(BANK, Clock.fixed(now, ZoneOffset.UTC));
    }
}
