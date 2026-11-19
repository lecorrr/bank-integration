package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.InvalidMessageException;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST a payment to bank-core's bank, then GET it to see the answers. */
@RestController
@RequestMapping("/api/payments")
class PaymentsController {

    private static final String HRYVNIA = "UAH";

    private final PaymentSender sender;
    private final SentPayments sent;

    PaymentsController(PaymentSender sender, SentPayments sent) {
        this.sender = sender;
        this.sent = sent;
    }

    @PostMapping
    ResponseEntity<SentResponse> send(@RequestBody PaymentRequest request) {
        Pacs008 payment = sender.send(request.toIban(), request.toName(), request.amount(),
                request.currency() == null ? HRYVNIA : request.currency(), request.description());
        return ResponseEntity.accepted().body(new SentResponse(payment.uetr(), payment.messageId()));
    }

    @GetMapping("/{uetr}")
    ResponseEntity<PaymentView> find(@PathVariable String uetr) {
        return sent.find(uetr)
                .map(PaymentView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{uetr}/status-requests")
    ResponseEntity<StatusRequestResponse> ask(@PathVariable String uetr) {
        return sent.find(uetr)
                .map(payment -> sender.ask(payment.payment()))
                .map(request -> ResponseEntity.accepted().body(new StatusRequestResponse(request.messageId())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler({IllegalArgumentException.class, InvalidMessageException.class})
    ResponseEntity<ErrorResponse> refuse(RuntimeException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    record PaymentRequest(String toIban, String toName, BigDecimal amount, String currency, String description) {
    }

    record SentResponse(String uetr, String messageId) {
    }

    record StatusRequestResponse(String messageId) {
    }

    record ErrorResponse(String error) {
    }

    record PaymentView(String uetr, String messageId, String toIban, String toName, BigDecimal amount,
            String currency, String description, int timesSent, List<AnswerView> answers) {

        static PaymentView of(SentPayments.Sent sent) {
            Pacs008 payment = sent.payment();
            return new PaymentView(payment.uetr(), payment.messageId(), payment.creditorIban(),
                    payment.creditorName(), payment.amount(), payment.currency(), payment.remittanceInformation(),
                    sent.timesSent(), sent.answers().stream().map(AnswerView::of).toList());
        }
    }

    record AnswerView(String messageId, String status, String reasonCode, Instant createdAt) {

        static AnswerView of(Pacs002 answer) {
            return new AnswerView(answer.messageId(), answer.status().name(), answer.reasonCode(),
                    answer.createdAt());
        }
    }
}
