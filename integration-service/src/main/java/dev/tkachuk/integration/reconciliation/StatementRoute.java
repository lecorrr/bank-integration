package dev.tkachuk.integration.reconciliation;

import dev.tkachuk.integration.InterbankProperties;
import dev.tkachuk.integration.messaging.Routes;
import dev.tkachuk.interbank.iso20022.Camt053;
import dev.tkachuk.interbank.iso20022.InvalidMessageException;
import java.time.Clock;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Flow C: the partner bank's daily camt.053 from its SFTP server is stored and reconciled, and every break is
 * logged. A file we can't use goes to error/; if storing fails, the file stays and is read again on the next poll.
 */
@Component
class StatementRoute extends RouteBuilder {

    static final String DONE = "done";
    static final String ERROR = "error";

    // Where the file is moved once the route is done with it; the endpoint's move option reads it.
    private static final String FOLDER = "statementFolder";

    private final ReconciliationProperties properties;
    private final Statements statements;
    private final Reconciler reconciler;
    private final Clock clock;
    private final String partnerBic;

    StatementRoute(ReconciliationProperties properties, InterbankProperties interbank, Statements statements,
            Reconciler reconciler, Clock clock) {
        this.properties = properties;
        this.statements = statements;
        this.reconciler = reconciler;
        this.clock = clock;
        this.partnerBic = InterbankProperties.bankCodeOf(properties.accountIban())
                .flatMap(interbank::participantBic)
                .orElseThrow(() -> new IllegalStateException(
                        "reconciliation.account-iban is not an account at any of interbank.participants"));
    }

    // Only *.xml: the partner bank uploads under a .part name and renames the file when it is complete. Oldest
    // name first, so the statements of several days come in order.
    static String endpointUri(ReconciliationProperties properties) {
        return properties.sftp().uri()
                + "&antInclude=*.xml"
                + "&sortBy=file:name"
                + "&charset=UTF-8"
                + "&delay=" + properties.pollInterval().toMillis()
                + "&move=${exchangeProperty." + FOLDER + "}/${file:name}";
    }

    @Override
    public void configure() {
        if (!properties.sftp().checksHostKey()) {
            log.warn("The SFTP server's host key is not checked; set reconciliation.sftp.known-hosts-file to check it");
        }

        onException(InvalidMessageException.class, UnusableStatementException.class)
                .handled(true)
                .process(this::refuse);

        from(endpointUri(properties))
                .routeId("camt053-from-sftp")
                .setProperty(FOLDER, constant(DONE))
                .convertBodyTo(String.class)
                .process(this::receive);
    }

    private void receive(Exchange exchange) {
        String fileName = exchange.getMessage().getHeader(Exchange.FILE_NAME, String.class);
        String xml = exchange.getMessage().getBody(String.class);
        Camt053 statement = Camt053.parse(xml);
        if (!statement.accountIban().equals(properties.accountIban())) {
            throw new UnusableStatementException("Statement " + statement.messageId() + " is for another account");
        }
        if (!statement.accountServicerBic().equals(partnerBic)) {
            throw new UnusableStatementException("Statement " + statement.messageId() + " comes from "
                    + statement.accountServicerBic() + ", which does not hold our account");
        }
        if (!statements.store(statement, xml, fileName, clock.instant())) {
            log.info("Statement {} in {} is stored already, skipping it", statement.messageId(), fileName);
            return;
        }
        Reconciliation reconciliation = reconciler.reconcile(statement.date()).orElseThrow(
                () -> new IllegalStateException("Statement " + statement.messageId() + " was stored, but not found"));
        logBreaks(reconciliation);
    }

    private void logBreaks(Reconciliation reconciliation) {
        StoredStatement statement = reconciliation.statement();
        log.info("Statement {} for {}: {} entries, {} breaks", statement.messageId(), statement.date(),
                reconciliation.lines(), reconciliation.breaks().size());
        for (Reconciliation.Item item : reconciliation.breaks()) {
            log.error("Statement {} for {}: {} for {} payment {}, booked {}, ours {}", statement.messageId(),
                    statement.date(), item.result(), item.direction(), item.uetr(), item.bookedAmount(),
                    item.ourAmount());
        }
        if (!reconciliation.continuesPrevious()) {
            log.error("Statement {} for {} opens at {}, but the statement for {} closed at {}", statement.messageId(),
                    statement.date(), statement.openingBalance(), reconciliation.previous().date(),
                    reconciliation.previous().closingBalance());
        }
    }

    private void refuse(Exchange exchange) {
        exchange.setProperty(FOLDER, ERROR);
        log.error("Moving statement file {} to {}/: {}", exchange.getMessage().getHeader(Exchange.FILE_NAME),
                ERROR, Routes.failureOf(exchange));
    }
}
