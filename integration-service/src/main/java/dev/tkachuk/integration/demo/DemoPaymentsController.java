package dev.tkachuk.integration.demo;

import dev.tkachuk.integration.events.EventJson;
import dev.tkachuk.integration.outgoing.TransferRequested;
import java.net.URI;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.kafka.KafkaConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts flow A by hand while bank-core cannot yet (step 3): POST publishes the demo transfer as bank-core will, a
 * TransferRequested event on Kafka, and GET shows the status that came back as a status event. Answers 202
 * because the status comes later.
 */
@RestController
@RequestMapping("/api/v1/demo/payments")
class DemoPaymentsController {

    private final DemoTransfers demoTransfers;
    private final ProducerTemplate producer;
    private final EventJson json;
    private final PaymentStatusStore statuses;

    DemoPaymentsController(DemoTransfers demoTransfers, ProducerTemplate producer, EventJson json,
            PaymentStatusStore statuses) {
        this.demoTransfers = demoTransfers;
        this.producer = producer;
        this.json = json;
        this.statuses = statuses;
    }

    @PostMapping
    ResponseEntity<PaymentStatusView> send() {
        TransferRequested transfer = demoTransfers.next();
        String transferId = transfer.transferId().toString();
        // Before publishing, so REQUESTED cannot overwrite a status event that arrives first.
        statuses.requested(transferId);
        try {
            // Keyed by the payer's IBAN, as bank-core will key it: one account's transfers keep their order.
            producer.sendBodyAndHeader("kafka:" + TransferRequested.TOPIC, json.write(transfer),
                    KafkaConstants.KEY, transfer.fromIban());
        } catch (RuntimeException e) {
            statuses.forget(transferId);
            throw e;
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/demo/payments/" + transferId))
                .body(new PaymentStatusView(transferId, PaymentStatusStore.REQUESTED));
    }

    @GetMapping("/{transferId}")
    ResponseEntity<PaymentStatusView> status(@PathVariable String transferId) {
        return statuses.statusOf(transferId)
                .map(status -> ResponseEntity.ok(new PaymentStatusView(transferId, status)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** @param status REQUESTED until the status event arrives, then SETTLED, REJECTED or PENDING */
    record PaymentStatusView(String transferId, String status) {
    }
}
