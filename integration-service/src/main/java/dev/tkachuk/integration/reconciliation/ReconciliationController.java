package dev.tkachuk.integration.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/reconciliations/2026-11-17: that day's statement checked against our payments; 404 without one. */
@RestController
@RequestMapping("/api/reconciliations")
class ReconciliationController {

    private final Reconciler reconciler;

    ReconciliationController(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @GetMapping("/{date}")
    ResponseEntity<ReconciliationView> find(@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return reconciler.reconcile(date)
                .map(ReconciliationView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record ReconciliationView(LocalDate date, String messageId, String currency, BigDecimal openingBalance,
            BigDecimal closingBalance, BigDecimal previousClosingBalance, boolean continuesPrevious, int breaks,
            List<Reconciliation.Item> items) {

        static ReconciliationView of(Reconciliation reconciliation) {
            StoredStatement statement = reconciliation.statement();
            StoredStatement previous = reconciliation.previous();
            return new ReconciliationView(statement.date(), statement.messageId(), statement.currency(),
                    statement.openingBalance(), statement.closingBalance(),
                    previous == null ? null : previous.closingBalance(), reconciliation.continuesPrevious(),
                    reconciliation.breaks().size(), reconciliation.items());
        }
    }
}
