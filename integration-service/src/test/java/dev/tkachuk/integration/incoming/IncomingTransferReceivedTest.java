package dev.tkachuk.integration.incoming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncomingTransferReceivedTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-11-18T09:15:02Z");

    @Test
    void tellsBankCoreWhoPaysWhomHowMuchAndWhy() {
        Pacs008 payment = TestPayments.payment();

        IncomingTransferReceived transfer = IncomingTransferReceived.of(payment, RECEIVED_AT);

        assertThat(transfer.uetr()).isEqualTo(UUID.fromString(TestPayments.UETR));
        assertThat(transfer.fromIban()).isEqualTo(TestPayments.PARTNER_CUSTOMER);
        assertThat(transfer.fromName()).isEqualTo("Olena Kovalenko");
        assertThat(transfer.toIban()).isEqualTo(TestPayments.OUR_CUSTOMER);
        assertThat(transfer.toName()).isEqualTo("Artem Demo");
        assertThat(transfer.amount()).isEqualByComparingTo("250.00");
        assertThat(transfer.currency()).isEqualTo("UAH");
        assertThat(transfer.description()).isEqualTo("For the concert tickets");
        assertThat(transfer.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void givesTheAmountInKopiykyWhateverDecimalsTheMessageWroteIt() {
        IncomingTransferReceived whole = IncomingTransferReceived.of(TestPayments.builder().amount("250").build(),
                RECEIVED_AT);
        IncomingTransferReceived longer = IncomingTransferReceived.of(
                TestPayments.builder().amount("250.500").build(), RECEIVED_AT);

        assertThat(whole.amount()).isEqualTo(new BigDecimal("250.00"));
        assertThat(longer.amount()).isEqualTo(new BigDecimal("250.50"));
    }

    @Test
    void needsEveryField() {
        assertThatThrownBy(() -> new IncomingTransferReceived(UUID.randomUUID(), TestPayments.PARTNER_CUSTOMER,
                "Olena Kovalenko", TestPayments.OUR_CUSTOMER, "Artem Demo", new BigDecimal("1.00"), "UAH", null,
                RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }
}
