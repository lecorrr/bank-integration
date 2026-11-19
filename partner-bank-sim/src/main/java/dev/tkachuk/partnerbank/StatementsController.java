package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Camt053;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/statements sends the open day's statement now instead of after midnight. The body may ask for faults
 * in it (StatementFaults); without one the statement is right.
 */
@RestController
@RequestMapping("/api/statements")
class StatementsController {

    private final StatementSender sender;

    StatementsController(StatementSender sender) {
        this.sender = sender;
    }

    @PostMapping
    ResponseEntity<SentView> send(@RequestBody(required = false) StatementFaults faults) {
        StatementSender.Sent sent = sender.send(faults == null ? StatementFaults.NONE : faults);
        return ResponseEntity.ok(SentView.of(sent));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> refuse(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    record SentView(String messageId, LocalDate date, String fileName, BigDecimal openingBalance,
            BigDecimal closingBalance, List<Camt053.Entry> entries) {

        static SentView of(StatementSender.Sent sent) {
            Camt053 statement = sent.statement();
            return new SentView(statement.messageId(), statement.date(), sent.fileName(),
                    statement.openingBalance(), statement.closingBalance(), statement.entries());
        }
    }

    record ErrorResponse(String error) {
    }
}
