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
 * also its routing key, "recipient.message", so a new message type is one more queue in this list. A queue
 * ending in ".dlq" holds what its bank could not act on, for a person to look at; no application reads it.
 *
 * <p>Both applications declare exactly these elements and Camel declares none of its own, so the broker
 * never sees two different versions of one queue (it would refuse the second with PRECONDITION_FAILED).
 */
public final class InterbankTopology {

    /** The direct exchange that carries every interbank message. */
    public static final String EXCHANGE = "interbank";

    // Flow A: payments from bank-core's customers to the partner bank.

    /** Customer credit transfers (pacs.008) from bank-core's bank to the partner bank. */
    public static final String PARTNER_PACS008 = "partner.pacs008";

    /** Payment status requests (pacs.028) from bank-core's bank, about payments whose answer is late. */
    public static final String PARTNER_PACS028 = "partner.pacs028";

    /** Payment status reports (pacs.002) from the partner bank back to bank-core's bank, for both of the above. */
    public static final String BANK_PACS002 = "bank.pacs002";

    /**
     * The status reports bank-core's bank could not act on: unreadable ones, ones about no payment of ours, and
     * ones that contradict a payment's final answer.
     */
    public static final String BANK_PACS002_DLQ = "bank.pacs002.dlq";

    // Flow B: payments from the partner bank's customers to bank-core's.

    /** Customer credit transfers (pacs.008) from the partner bank to bank-core's customers. */
    public static final String BANK_PACS008 = "bank.pacs008";

    /** Payment status requests (pacs.028) from the partner bank, about its payments to bank-core's customers. */
    public static final String BANK_PACS028 = "bank.pacs028";

    /** Payment status reports (pacs.002) from bank-core's bank back to the partner bank, for both of the above. */
    public static final String PARTNER_PACS002 = "partner.pacs002";

    /** The payments to bank-core's customers that bank-core's bank could not read. */
    public static final String BANK_PACS008_DLQ = "bank.pacs008.dlq";

    /** The status requests bank-core's bank could not read. */
    public static final String BANK_PACS028_DLQ = "bank.pacs028.dlq";

    private static final List<String> QUEUES = List.of(
            PARTNER_PACS008, PARTNER_PACS028, BANK_PACS002, BANK_PACS002_DLQ,
            BANK_PACS008, BANK_PACS028, PARTNER_PACS002, BANK_PACS008_DLQ, BANK_PACS028_DLQ);

    private InterbankTopology() {
    }

    /** Every queue's name, which is also its routing key. */
    public static List<String> queues() {
        return QUEUES;
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
