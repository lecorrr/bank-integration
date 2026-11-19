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

/**
 * The partner bank's payments to bank-core's customers (flow B), over HTTP, for demos and tests:
 *
 * <pre>
 * POST /api/payments                         sends a payment; 202 with its UETR and MsgId
 * GET  /api/payments/{uetr}                  the payment and every answer about it
 * POST /api/payments/{uetr}/status-requests  asks about it with a pacs.028; 202 with the request's MsgId
 * </pre>
 *
 * 202, because the answer comes later, over RabbitMQ: GET shows it once it has.
 */
@RestController
@RequestMapping("/api/payments")
class PaymentsController {

    /** SEP settles hryvnia, so a payment without a currency is in hryvnia. */
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

    /** A payment a pacs.008 cannot carry, such as one without a payee name: the reason names the field. */
    @ExceptionHandler({IllegalArgumentException.class, InvalidMessageException.class})
    ResponseEntity<ErrorResponse> refuse(RuntimeException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    /** @param currency UAH when left out */
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

    /** @param reasonCode null for an answer without one, such as ACSC */
    record AnswerView(String messageId, String status, String reasonCode, Instant createdAt) {

        static AnswerView of(Pacs002 answer) {
            return new AnswerView(answer.messageId(), answer.status().name(), answer.reasonCode(),
                    answer.createdAt());
        }
    }
}
