package dev.tkachuk.integration.outgoing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TransferStatusChangedTest {

    private static final Instant REPORTED_AT = Instant.parse("2026-11-17T08:30:05Z");

    @ParameterizedTest(name = "{0} means {1}")
    @CsvSource({"ACSC, SETTLED", "RJCT, REJECTED", "PDNG, PENDING"})
    void tellsBankCoreWhatThePartnersAnswerMeans(TransactionStatus answer, TransferStatus status) {
        TransferStatusChanged event = TransferStatusChanged.from(reportWith(answer));

        assertThat(event.status()).isEqualTo(status);
    }

    @Test
    void findsTheTransferByTheUetrAndKeepsTheReportsIdAndTime() {
        TransferStatusChanged event = TransferStatusChanged.from(reportWith(TransactionStatus.ACSC));

        assertThat(event.transferId()).isEqualTo(TestTransfers.TRANSFER_ID);
        assertThat(event.reportId()).isEqualTo("67a65fa611fe4db9bc67b8271c441990");
        assertThat(event.reportedAt()).isEqualTo(REPORTED_AT);
    }

    @Test
    void refusesAnEventWithoutAStatus() {
        assertThatThrownBy(() -> new TransferStatusChanged(TestTransfers.TRANSFER_ID, null, "ANSWER-1", REPORTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    private static Pacs002 reportWith(TransactionStatus status) {
        return new Pacs002("67a65fa611fe4db9bc67b8271c441990", REPORTED_AT, "0f5bd7c23a4e4c1b9d0e6f2a8b1c3d4e",
                "NOTPROVIDED", "0f5bd7c23a4e4c1b9d0e6f2a8b1c3d4e", TestTransfers.TRANSFER_ID.toString(), status,
                null);
    }
}
