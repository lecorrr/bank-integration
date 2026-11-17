package dev.tkachuk.integration.demo;

import dev.tkachuk.integration.events.EventJson;
import dev.tkachuk.integration.outgoing.TransferStatusChanged;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Reads the status events the way bank-core will from step 3, in a consumer group of its own, so the demo sees
 * every status without taking any from another reader. The rest of the service does not know the demo exists.
 */
@Component
class DemoStatusRoutes extends RouteBuilder {

    static final String CONSUMER_GROUP = "demo-bank-core";

    private final EventJson json;
    private final PaymentStatusStore statuses;

    DemoStatusRoutes(EventJson json, PaymentStatusStore statuses) {
        this.json = json;
        this.statuses = statuses;
    }

    @Override
    public void configure() {
        from("kafka:" + TransferStatusChanged.TOPIC + "?groupId=" + CONSUMER_GROUP + "&autoOffsetReset=earliest")
                .routeId("demo-statuses")
                .process(exchange -> statuses.record(
                        json.read(exchange.getMessage().getBody(String.class), TransferStatusChanged.class)));
    }
}
