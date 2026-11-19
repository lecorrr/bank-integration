package dev.tkachuk.interbank;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * The RabbitMQ topology both banks use: one direct exchange and a quorum queue per recipient and message type.
 * A queue's name is also its routing key.
 */
public final class InterbankTopology {

    public static final String EXCHANGE = "interbank";

    // Flow A: bank-core's customers paying the partner bank
    public static final String PARTNER_PACS008 = "partner.pacs008";
    public static final String PARTNER_PACS028 = "partner.pacs028";
    public static final String BANK_PACS002 = "bank.pacs002";
    public static final String BANK_PACS002_DLQ = "bank.pacs002.dlq";

    // Flow B: the partner bank paying bank-core's customers
    public static final String BANK_PACS008 = "bank.pacs008";
    public static final String BANK_PACS028 = "bank.pacs028";
    public static final String PARTNER_PACS002 = "partner.pacs002";
    public static final String BANK_PACS008_DLQ = "bank.pacs008.dlq";
    public static final String BANK_PACS028_DLQ = "bank.pacs028.dlq";

    private static final List<String> QUEUES = List.of(
            PARTNER_PACS008, PARTNER_PACS028, BANK_PACS002, BANK_PACS002_DLQ,
            BANK_PACS008, BANK_PACS028, PARTNER_PACS002, BANK_PACS008_DLQ, BANK_PACS028_DLQ);

    private InterbankTopology() {
    }

    public static List<String> queues() {
        return QUEUES;
    }

    /** Registered as a bean in both applications and declared by Spring's RabbitAdmin. */
    public static Declarables declarables() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(exchange);
        for (String name : QUEUES) {
            Queue queue = QueueBuilder.durable(name).quorum().build();
            declarables.add(queue);
            declarables.add(BindingBuilder.bind(queue).to(exchange).with(name));
        }
        return new Declarables(declarables);
    }
}
