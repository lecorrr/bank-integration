package dev.tkachuk.integration.interbank;

import dev.tkachuk.interbank.iso20022.Pacs008;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.springframework.stereotype.Component;

/** The one way the rest of the application sends a payment to another bank; it never sees Camel endpoints. */
@Component
public class InterbankGateway {

    private final ProducerTemplate producer;
    private final PaymentStatusStore statuses;

    InterbankGateway(ProducerTemplate producer, PaymentStatusStore statuses) {
        this.producer = producer;
        this.statuses = statuses;
    }

    /**
     * Sends the payment as a pacs.008. It is remembered before it is sent, so even an instant pacs.002 finds it,
     * and forgotten again if sending fails, so a payment that never left is not shown as SENT.
     *
     * @throws org.apache.camel.CamelExecutionException when RabbitMQ did not take the message
     */
    public void send(Pacs008 payment) {
        String messageId = payment.messageId();
        statuses.sent(messageId);
        try {
            // The AMQP message id: the receiving bank can drop a duplicate delivery by it (step 4).
            producer.sendBodyAndHeader(InterbankRoutes.SEND_PACS008, payment.toXml(),
                    SpringRabbitMQConstants.MESSAGE_ID, messageId);
        } catch (RuntimeException e) {
            statuses.forget(messageId);
            throw e;
        }
    }
}
