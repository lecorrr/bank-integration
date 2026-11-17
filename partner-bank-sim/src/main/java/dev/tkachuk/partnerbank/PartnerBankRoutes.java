package dev.tkachuk.partnerbank;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;

import dev.tkachuk.interbank.iso20022.Pacs002;
import org.apache.camel.Message;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.springframework.stereotype.Component;

/** Every pacs.008 that reaches the partner bank gets a pacs.002 back on bank-core's queue. */
@Component
class PartnerBankRoutes extends RouteBuilder {

    private final StatusReports statusReports;

    PartnerBankRoutes(StatusReports statusReports) {
        this.statusReports = statusReports;
    }

    @Override
    public void configure() {
        from("spring-rabbitmq:" + EXCHANGE + "?queues=" + PARTNER_PACS008).routeId("answer-pacs008")
                .convertBodyTo(String.class)
                .process(exchange -> {
                    Message message = exchange.getMessage();
                    Pacs002 report = statusReports.answer(message.getBody(String.class));
                    // Drop the incoming pacs.008's headers: its AMQP message id must not travel on with the answer.
                    message.removeHeaders("*");
                    message.setHeader(SpringRabbitMQConstants.MESSAGE_ID, report.messageId());
                    message.setBody(report.toXml());
                })
                .to("spring-rabbitmq:" + EXCHANGE + "?routingKey=" + BANK_PACS002);
    }
}
