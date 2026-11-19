package dev.tkachuk.integration.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A row of statement, without the XML. */
record StoredStatement(String messageId, LocalDate date, String currency, BigDecimal openingBalance,
        BigDecimal closingBalance) {
}
