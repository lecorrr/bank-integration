package dev.tkachuk.integration.outgoing;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;

import dev.tkachuk.integration.events.EventJson;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Message;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.springframework.stereotype.Component;

/**
 * Flow A, a payment to another bank: bank-core's transfer event comes from Kafka and goes out as a pacs.008 over
 * RabbitMQ, and the other bank's pacs.002 comes back and goes to Kafka as a status event. Each hop clears the
 * headers it received, so nothing internal to our bank reaches the other bank, and nothing of theirs reaches
 * bank-core. Queues and bindings come from InterbankTopology; Camel declares nothing itself (application.yml).
 */
@Component
class OutgoingTransferRoutes extends RouteBuilder {

    /** Kafka keeps the offsets per group, so after a restart the service carries on where it stopped. */
    static final String CONSUMER_GROUP = "integration-service";

    private final EventJson json;
    private final Pacs008Mapper mapper;

    OutgoingTransferRoutes(EventJson json, Pacs008Mapper mapper) {
        this.json = json;
        this.mapper = mapper;
    }

    @Override
    public void configure() {
        // A new group starts at the oldest event, so a transfer published before the first start is not missed.
        from("kafka:" + TransferRequested.TOPIC + "?groupId=" + CONSUMER_GROUP + "&autoOffsetReset=earliest")
                .routeId("transfer-to-pacs008")
                .process(this::toPacs008)
                .log(LoggingLevel.INFO, "Sending pacs.008 ${header.CamelSpringRabbitmqMessageId} to the partner bank")
                .to("spring-rabbitmq:" + EXCHANGE + "?routingKey=" + PARTNER_PACS008);

        from("spring-rabbitmq:" + EXCHANGE + "?queues=" + BANK_PACS002)
                .routeId("pacs002-to-status")
                .convertBodyTo(String.class)
                .process(this::toStatusEvent)
                .to("kafka:" + TransferStatusChanged.TOPIC);
    }

    private void toPacs008(Exchange exchange) {
        Message message = exchange.getMessage();
        TransferRequested transfer = json.read(message.getBody(String.class), TransferRequested.class);
        Pacs008 payment = mapper.toPacs008(transfer);
        message.removeHeaders("*");
        // The AMQP message id: the receiving bank can drop a duplicate delivery by it (step 4).
        message.setHeader(SpringRabbitMQConstants.MESSAGE_ID, payment.messageId());
        message.setBody(payment.toXml());
    }

    private void toStatusEvent(Exchange exchange) {
        Message message = exchange.getMessage();
        Pacs002 report = Pacs002.parse(message.getBody(String.class));
        TransferStatusChanged status = TransferStatusChanged.from(report);
        log.info("Transfer {} is {} (pacs.002 {})", status.transferId(), status.status(), report.messageId());
        message.removeHeaders("*");
        // Keyed by transfer, so the statuses of one transfer stay in order.
        message.setHeader(KafkaConstants.KEY, status.transferId().toString());
        message.setBody(json.write(status));
    }
}
