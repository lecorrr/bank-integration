package dev.tkachuk.integration.interbank;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;

import dev.tkachuk.interbank.iso20022.Pacs002;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * The two Camel routes of the walking skeleton: pacs.008 out to the partner bank, pacs.002 back in.
 * Queues and bindings come from InterbankTopology; Camel declares nothing itself (application.yml).
 */
@Component
class InterbankRoutes extends RouteBuilder {

    /** Where the application hands over a pacs.008 to be sent; the message id goes in a header for the logs. */
    static final String SEND_PACS008 = "direct:send-pacs008";

    private final PaymentStatusStore statuses;

    InterbankRoutes(PaymentStatusStore statuses) {
        this.statuses = statuses;
    }

    @Override
    public void configure() {
        from(SEND_PACS008).routeId("send-pacs008")
                .log(LoggingLevel.INFO, "Sending pacs.008 ${header.CamelSpringRabbitmqMessageId} to the partner bank")
                .to("spring-rabbitmq:" + EXCHANGE + "?routingKey=" + PARTNER_PACS008);

        from("spring-rabbitmq:" + EXCHANGE + "?queues=" + BANK_PACS002).routeId("receive-pacs002")
                .convertBodyTo(String.class)
                .process(exchange -> statuses.record(Pacs002.parse(exchange.getMessage().getBody(String.class))));
    }
}
