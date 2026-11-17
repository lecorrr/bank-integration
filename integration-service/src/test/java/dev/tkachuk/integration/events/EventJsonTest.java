package dev.tkachuk.integration.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.outgoing.TestTransfers;
import dev.tkachuk.integration.outgoing.TransferRequested;
import dev.tkachuk.integration.outgoing.TransferStatus;
import dev.tkachuk.integration.outgoing.TransferStatusChanged;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventJsonTest {

    /** The transfer as bank-core writes it, with the field order and spacing of a hand-written example. */
    private static final String TRANSFER = """
            {"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e",
             "fromIban": "UA843999990000000000000000001", "fromName": "Artem Tkachuk",
             "toIban": "UA643888880000000000000000777", "toName": "Olena Kovalenko",
             "amount": 150.00, "currency": "UAH", "description": "Rent for November",
             "requestedAt": "2026-11-17T08:30:00Z"}
            """;

    private final EventJson json = new EventJson();

    @Test
    void readsATransferAsBankCoreWritesIt() {
        assertThat(json.read(TRANSFER, TransferRequested.class)).isEqualTo(TestTransfers.transfer());
    }

    @Test
    void readsBackWhatItWritesWithTheAmountExact() {
        TransferRequested transfer = TestTransfers.transfer();

        assertThat(json.read(json.write(transfer), TransferRequested.class)).isEqualTo(transfer);
    }

    @Test
    void writesDatesAsIsoTextAndAmountsAsNumbers() {
        Map<?, ?> status = json.read(json.write(new TransferStatusChanged(TestTransfers.TRANSFER_ID,
                TransferStatus.SETTLED, "ANSWER-1", Instant.parse("2026-11-17T08:30:05Z"))), Map.class);
        Map<?, ?> transfer = json.read(json.write(TestTransfers.transfer()), Map.class);

        assertThat(status.get("reportedAt")).isEqualTo("2026-11-17T08:30:05Z");
        assertThat(status.get("status")).isEqualTo("SETTLED");
        assertThat(transfer.get("amount")).isInstanceOf(Number.class);
    }

    @Test
    void ignoresAFieldItDoesNotKnow() {
        String withAChannel = TRANSFER.replace("\"currency\"", "\"channel\": \"mobile\", \"currency\"");

        assertThat(json.read(withAChannel, TransferRequested.class)).isEqualTo(TestTransfers.transfer());
    }

    @Test
    void refusesAnEventThatBreaksItsRulesAndSaysWhich() {
        String negative = TRANSFER.replace("150.00", "-150.00");

        assertThatThrownBy(() -> json.read(negative, TransferRequested.class))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("amount must be positive");
    }

    @Test
    void refusesTextThatIsNotJson() {
        assertThatThrownBy(() -> json.read("150 UAH to Olena, please", TransferRequested.class))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("Not a valid TransferRequested");
        assertThatThrownBy(() -> json.read(" ", TransferRequested.class))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("empty");
    }
}
