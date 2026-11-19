package dev.tkachuk.integration.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.integration.ServiceIntegrationTest;
import dev.tkachuk.integration.TestSftp;
import dev.tkachuk.interbank.iso20022.Camt053;
import dev.tkachuk.interbank.iso20022.CreditDebit;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flow C end to end: a statement file on the SFTP server, the report over REST. Each test uses days of its own,
 * since the tests share one database.
 */
@ServiceIntegrationTest
class StatementFlowIT {

    private static final String OUR_ACCOUNT = "UA853888880000000000000000399";
    private static final String PARTNER_BIC = "PRTBUAUKXXX";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final AtomicInteger references = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TestSftp sftp;

    @Test
    void shouldReconcileStatementAndShowEveryBreak() throws Exception {
        LocalDate day = LocalDate.parse("2026-10-01");
        UUID settled = outgoing("SETTLED", "150.00", day);
        UUID otherAmount = outgoing("SETTLED", "200.00", day);
        UUID waiting = outgoing("SENT", "75.00", day);
        UUID notBooked = outgoing("SETTLED", "50.00", day);
        UUID accepted = acceptedIncoming("300.00", day);
        UUID unknown = UUID.randomUUID();
        Camt053 statement = statement(day, "1000.00", List.of(
                entry(CreditDebit.DBIT, settled, "150.00"),
                entry(CreditDebit.DBIT, otherAmount, "210.00"),
                entry(CreditDebit.DBIT, waiting, "75.00"),
                entry(CreditDebit.CRDT, accepted, "300.00"),
                entry(CreditDebit.CRDT, accepted, "300.00"),
                entry(CreditDebit.DBIT, unknown, "10.00")));

        awaitIn(StatementRoute.DONE, drop(statement));

        ReconciliationController.ReconciliationView report = reconciliation(day);
        assertThat(report.messageId()).isEqualTo(statement.messageId());
        assertThat(report.items()).extracting(item -> item.uetr() + " " + item.result()).containsExactly(
                settled + " MATCHED",
                otherAmount + " AMOUNT_MISMATCH",
                waiting + " NOT_SETTLED",
                accepted + " MATCHED",
                accepted + " DUPLICATE",
                unknown + " UNKNOWN",
                notBooked + " MISSING");
        assertThat(report.items().get(1).bookedAmount()).isEqualByComparingTo("210.00");
        assertThat(report.items().get(1).ourAmount()).isEqualByComparingTo("200.00");
        assertThat(report.breaks()).isEqualTo(5);
        assertThat(report.closingBalance()).isEqualByComparingTo("1155.00");
    }

    @Test
    void shouldMatchAnswerThatCameAfterStatement() throws Exception {
        LocalDate day = LocalDate.parse("2026-10-05");
        UUID late = outgoing("PENDING", "80.00", day);
        awaitIn(StatementRoute.DONE, drop(statement(day, "1000.00", List.of(entry(CreditDebit.DBIT, late, "80.00")))));
        assertThat(reconciliation(day).items().getFirst().result()).isEqualTo(MatchResult.NOT_SETTLED);

        jdbc.sql("UPDATE outgoing_payment SET state = 'SETTLED' WHERE transfer_id = :transferId")
                .param("transferId", late)
                .update();

        assertThat(reconciliation(day).items().getFirst().result()).isEqualTo(MatchResult.MATCHED);
    }

    @Test
    void shouldShowWhetherStatementContinuesPreviousOne() throws Exception {
        LocalDate first = LocalDate.parse("2025-01-01");
        awaitIn(StatementRoute.DONE, drop(statement(first, "1000.00", List.of())));
        awaitIn(StatementRoute.DONE, drop(statement(first.plusDays(1), "1000.00", List.of())));
        awaitIn(StatementRoute.DONE, drop(statement(first.plusDays(2), "900.00", List.of())));

        assertThat(reconciliation(first).continuesPrevious()).as("the first statement").isTrue();
        assertThat(reconciliation(first.plusDays(1)).continuesPrevious()).isTrue();
        ReconciliationController.ReconciliationView gap = reconciliation(first.plusDays(2));
        assertThat(gap.continuesPrevious()).isFalse();
        assertThat(gap.previousClosingBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void shouldKeepFirstStatementOfDayAndRefuseAnother() throws Exception {
        LocalDate day = LocalDate.parse("2026-10-10");
        Camt053 first = statement(day, "1000.00", List.of());
        awaitIn(StatementRoute.DONE, drop(first));

        awaitIn(StatementRoute.ERROR, drop(statement(day, "500.00", List.of())));

        assertThat(reconciliation(day).messageId()).isEqualTo(first.messageId());
    }

    @Test
    void shouldStoreSameStatementOnce() throws Exception {
        LocalDate day = LocalDate.parse("2026-10-12");
        Camt053 statement = statement(day, "1000.00",
                List.of(entry(CreditDebit.DBIT, outgoing("SETTLED", "20.00", day), "20.00")));
        awaitIn(StatementRoute.DONE, drop(statement));

        String copy = "copy-of-" + statement.messageId() + ".xml";
        sftp.drop(copy, statement.toXml());

        awaitIn(StatementRoute.DONE, copy);
        assertThat(reconciliation(day).items()).hasSize(1);
    }

    @Test
    void shouldMoveFileItCannotUseToErrorFolder() throws Exception {
        String notAStatement = "camt053-broken-" + UUID.randomUUID() + ".xml";
        Camt053 otherAccount = new Camt053(newMessageId(), Instant.now().truncatedTo(ChronoUnit.SECONDS), "STMT-X",
                "UA643888880000000000000000777", PARTNER_BIC, "UAH", LocalDate.parse("2026-10-15"),
                BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        sftp.drop(notAStatement, "<Document>not a statement</Document>");
        String forAnotherAccount = drop(otherAccount);

        awaitIn(StatementRoute.ERROR, notAStatement);
        awaitIn(StatementRoute.ERROR, forAnotherAccount);
        mvc.perform(get("/api/reconciliations/2026-10-15")).andExpect(status().isNotFound());
    }

    @Test
    void shouldAnswerNotFoundForDayWithoutStatement() throws Exception {
        mvc.perform(get("/api/reconciliations/2020-01-01")).andExpect(status().isNotFound());
        mvc.perform(get("/api/reconciliations/yesterday")).andExpect(status().isBadRequest());
    }

    @Test
    void shouldNameReportFieldsForClients() throws Exception {
        LocalDate day = LocalDate.parse("2026-10-20");
        awaitIn(StatementRoute.DONE, drop(statement(day, "1000.00",
                List.of(entry(CreditDebit.DBIT, UUID.randomUUID(), "1.00")))));

        String body = mvc.perform(get("/api/reconciliations/" + day)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> report = new JsonMapper().readValue(body, Map.class);
        assertThat(report.get("date")).isEqualTo("2026-10-20");
        assertThat(namesIn(report)).containsExactlyInAnyOrder("date", "messageId", "currency", "openingBalance",
                "closingBalance", "previousClosingBalance", "continuesPrevious", "breaks", "items");
        Map<?, ?> item = (Map<?, ?>) ((List<?>) report.get("items")).getFirst();
        assertThat(namesIn(item)).containsExactlyInAnyOrder("result", "direction", "uetr", "reference",
                "bookedAmount", "ourAmount");
        assertThat(item.get("result")).isEqualTo("UNKNOWN");
    }

    private static List<String> namesIn(Map<?, ?> json) {
        return json.keySet().stream().map(String::valueOf).toList();
    }

    private ReconciliationController.ReconciliationView reconciliation(LocalDate day) throws Exception {
        String body = mvc.perform(get("/api/reconciliations/" + day)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new JsonMapper().readValue(body, ReconciliationController.ReconciliationView.class);
    }

    private String drop(Camt053 statement) {
        String fileName = "camt053-" + statement.date() + "-" + statement.messageId() + ".xml";
        sftp.drop(fileName, statement.toXml());
        return fileName;
    }

    private void awaitIn(String folder, String fileName) {
        await().atMost(TIMEOUT).until(() -> sftp.files(folder).contains(fileName));
    }

    private Camt053 statement(LocalDate day, String openingBalance, List<Camt053.Entry> entries) {
        BigDecimal closingBalance = new BigDecimal(openingBalance);
        for (Camt053.Entry entry : entries) {
            closingBalance = closingBalance.add(entry.signedAmount());
        }
        return new Camt053(newMessageId(), Instant.now().truncatedTo(ChronoUnit.SECONDS), "STMT-" + day, OUR_ACCOUNT,
                PARTNER_BIC, "UAH", day, new BigDecimal(openingBalance), closingBalance, entries);
    }

    private Camt053.Entry entry(CreditDebit direction, UUID uetr, String amount) {
        return new Camt053.Entry("REF-" + references.incrementAndGet(), direction, new BigDecimal(amount),
                uetr.toString(), "NOTPROVIDED");
    }

    private UUID outgoing(String state, String amount, LocalDate settlementDate) {
        UUID transferId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outgoing_payment (transfer_id, message_id, pacs008, state, created_at,
                            settlement_date, amount, currency)
                        VALUES (:transferId, :messageId, '<pacs008/>', :state, now(), :settlementDate, :amount,
                            'UAH')""")
                .param("transferId", transferId)
                .param("messageId", transferId.toString().replace("-", ""))
                .param("state", state)
                .param("settlementDate", settlementDate)
                .param("amount", new BigDecimal(amount))
                .update();
        return transferId;
    }

    private UUID acceptedIncoming(String amount, LocalDate settlementDate) {
        UUID uetr = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO incoming_payment (uetr, sender_bic, message_id, pacs008, settlement_date, amount,
                            currency, received_at, status, report_id, answered_at)
                        VALUES (:uetr, :senderBic, :messageId, '<pacs008/>', :settlementDate, :amount, 'UAH', now(),
                            'ACSC', :reportId, now())""")
                .param("uetr", uetr)
                .param("senderBic", PARTNER_BIC)
                .param("messageId", uetr.toString().replace("-", ""))
                .param("settlementDate", settlementDate)
                .param("amount", new BigDecimal(amount))
                .param("reportId", "ANSWER-" + uetr.toString().substring(0, 8))
                .update();
        return uetr;
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
