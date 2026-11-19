package dev.tkachuk.integration.reconciliation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Works out a day's reconciliation from what is stored, each time it is asked for. Nothing is saved, so an answer
 * that comes after the statement, or a payment booked in a later statement, shows up the next time.
 */
@Component
class Reconciler {

    private final Statements statements;
    private final String accountIban;

    Reconciler(Statements statements, ReconciliationProperties properties) {
        this.statements = statements;
        this.accountIban = properties.accountIban();
    }

    Optional<Reconciliation> reconcile(LocalDate date) {
        return statements.find(accountIban, date).map(this::reconcile);
    }

    private Reconciliation reconcile(StoredStatement statement) {
        List<Reconciliation.Item> items = new ArrayList<>();
        for (StatementLine line : statements.lines(statement.messageId())) {
            items.add(Reconciliation.Item.of(line, MatchResult.of(line, statement.currency())));
        }
        for (UnbookedPayment payment : statements.unbooked(statement.date())) {
            items.add(Reconciliation.Item.missing(payment));
        }
        StoredStatement previous = statements.findPrevious(accountIban, statement.date()).orElse(null);
        return new Reconciliation(statement, previous, items);
    }
}
