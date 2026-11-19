package dev.tkachuk.integration.incoming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.incoming.IncomingTransferDecided.Decision;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IncomingTransferDecidedTest {

    private static final UUID UETR = UUID.fromString(TestPayments.UETR);
    private static final UUID TRANSFER_ID = UUID.fromString("9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f");
    private static final Instant DECIDED_AT = Instant.parse("2026-11-18T09:15:03Z");

    @Test
    void shouldMapCreditedToAcsc() {
        IncomingTransferDecided credited = new IncomingTransferDecided(UETR, TRANSFER_ID, Decision.CREDITED, null,
                DECIDED_AT);

        assertThat(credited.status()).isEqualTo(TransactionStatus.ACSC);
        assertThat(credited.reasonCode()).isNull();
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            "UNKNOWN_ACCOUNT, AC01",
            "CURRENCY_MISMATCH, CURR"})
    void shouldMapRejectedToIsoReasonCode(String reason, String reasonCode) {
        IncomingTransferDecided rejected = new IncomingTransferDecided(UETR, TRANSFER_ID, Decision.REJECTED, reason,
                DECIDED_AT);

        assertThat(rejected.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(rejected.reasonCode()).isEqualTo(reasonCode);
    }

    @Test
    void shouldMapUnknownReasonToMs03() {
        IncomingTransferDecided rejected = new IncomingTransferDecided(UETR, TRANSFER_ID, Decision.REJECTED,
                "SOMETHING_NEW", DECIDED_AT);

        assertThat(rejected.reasonCode()).isEqualTo(StatusReason.NOT_SPECIFIED);
    }

    @Test
    void shouldRejectReasonForCredited() {
        assertThatThrownBy(() -> new IncomingTransferDecided(UETR, TRANSFER_ID, Decision.CREDITED,
                "UNKNOWN_ACCOUNT", DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be null");
    }

    @Test
    void shouldRequireReasonForRejected() {
        for (String reason : new String[] {null, " "}) {
            assertThatThrownBy(() -> new IncomingTransferDecided(UETR, TRANSFER_ID, Decision.REJECTED, reason,
                    DECIDED_AT))
                    .as("reason <%s>", reason)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reason is required");
        }
    }

    @Test
    void shouldRequireTransferId() {
        assertThatThrownBy(() -> new IncomingTransferDecided(UETR, null, Decision.CREDITED, null, DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }
}
