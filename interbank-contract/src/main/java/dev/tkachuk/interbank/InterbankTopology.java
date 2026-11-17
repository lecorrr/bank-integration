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
 * The RabbitMQ half of what the two banks agree on. Each bank reads only its own queues. A queue's name is
 * also its routing key, "recipient.message", so a new message type is one more queue in this list.
 *
 * <p>Both applications declare exactly these elements and Camel declares none of its own, so the broker
 * never sees two different versions of one queue (it would refuse the second with PRECONDITION_FAILED).
 */
public final class InterbankTopology {

    /** The direct exchange that carries every interbank message. */
    public static final String EXCHANGE = "interbank";

    /** Customer credit transfers (pacs.008) from bank-core's bank to the partner bank. */
    public static final String PARTNER_PACS008 = "partner.pacs008";

    /** Payment status reports (pacs.002) from the partner bank back to bank-core's bank. */
    public static final String BANK_PACS002 = "bank.pacs002";

    private static final List<String> QUEUES = List.of(PARTNER_PACS008, BANK_PACS002);

    private InterbankTopology() {
    }

    /**
     * The exchange, one durable quorum queue per message flow, and a binding from each queue to the exchange.
     * Quorum queues are replicated, survive a broker restart, and stop redelivering a message that keeps
     * failing (the delivery limit).
     */
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
