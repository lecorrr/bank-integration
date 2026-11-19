package dev.tkachuk.partnerbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.interbank.iso20022.Camt053;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatementSenderTest {

    // 10:00 in Kyiv
    private static final Instant MORNING = Instant.parse("2026-11-17T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-17");

    private final TestClock clock = new TestClock(MORNING);
    private final CorrespondentAccount account = new CorrespondentAccount(clock, new BigDecimal("1000.00"), 100);
    private final StatementWriter writer =
            new StatementWriter(account, TestStatements.PROPERTIES, TestStatements.SENDING, clock);
    private final Map<String, String> uploaded = new LinkedHashMap<>();
    private final StatementSender sender = new StatementSender(account, writer, uploaded::put, clock);

    @Test
    void shouldUploadOpenDayAndCloseIt() {
        account.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "150.00"));

        StatementSender.Sent sent = sender.send(StatementFaults.NONE);

        assertThat(sent.fileName()).isEqualTo("camt053-2026-11-17-" + sent.statement().messageId() + ".xml");
        assertThat(Camt053.parse(uploaded.get(sent.fileName()))).isEqualTo(sent.statement());
        assertThat(account.openDay().date()).isEqualTo(TODAY.plusDays(1));
        assertThat(account.openDay().openingBalance()).isEqualByComparingTo("850.00");
    }

    @Test
    void shouldKeepDayOpenWhenUploadFails() {
        StatementSender failing = new StatementSender(account, writer, (fileName, xml) -> {
            throw new IllegalStateException("SFTP server is down");
        }, clock);

        assertThatThrownBy(() -> failing.send(StatementFaults.NONE)).hasMessageContaining("is down");
        assertThat(account.openDay().date()).isEqualTo(TODAY);
    }

    @Test
    void shouldSendEveryDayBeforeTodayOldestFirst() {
        account.debit(TestPayments.fromBankCore(UUID.randomUUID().toString(), "150.00"));
        clock.set(MORNING.plus(Duration.ofDays(3)));

        sender.sendDueStatements();

        List<Camt053> statements = uploaded.values().stream().map(Camt053::parse).toList();
        assertThat(statements).extracting(Camt053::date).containsExactly(TODAY, TODAY.plusDays(1), TODAY.plusDays(2));
        assertThat(statements.get(1).openingBalance()).isEqualByComparingTo(statements.get(0).closingBalance());
        assertThat(account.openDay().date()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void shouldSendNothingBeforeTheDayIsOver() {
        sender.sendDueStatements();

        assertThat(uploaded).isEmpty();
    }
}
