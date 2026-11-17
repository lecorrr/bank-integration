package dev.tkachuk.integration.demo;

import dev.tkachuk.integration.interbank.InterbankGateway;
import dev.tkachuk.integration.interbank.PaymentStatusStore;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts the walking skeleton by hand: POST sends the demo payment as a pacs.008, GET shows whether the
 * partner bank's pacs.002 has arrived. Answers 202 because the answer comes later, over RabbitMQ.
 */
@RestController
@RequestMapping("/api/v1/demo/payments")
class DemoPaymentsController {

    private final DemoPayments demoPayments;
    private final InterbankGateway gateway;
    private final PaymentStatusStore statuses;

    DemoPaymentsController(DemoPayments demoPayments, InterbankGateway gateway, PaymentStatusStore statuses) {
        this.demoPayments = demoPayments;
        this.gateway = gateway;
        this.statuses = statuses;
    }

    @PostMapping
    ResponseEntity<PaymentStatusView> send() {
        Pacs008 payment = demoPayments.next();
        gateway.send(payment);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/demo/payments/" + payment.messageId()))
                .body(new PaymentStatusView(payment.messageId(), PaymentStatusStore.SENT));
    }

    @GetMapping("/{messageId}")
    ResponseEntity<PaymentStatusView> status(@PathVariable String messageId) {
        return statuses.statusOf(messageId)
                .map(status -> ResponseEntity.ok(new PaymentStatusView(messageId, status)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** @param status SENT until the pacs.002 arrives, then its transaction status, e.g. ACSC */
    record PaymentStatusView(String messageId, String status) {
    }
}
