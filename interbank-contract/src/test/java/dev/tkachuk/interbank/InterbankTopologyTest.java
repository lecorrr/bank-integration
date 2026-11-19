package dev.tkachuk.interbank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

class InterbankTopologyTest {

    private final Declarables topology = InterbankTopology.declarables();

    @Test
    void shouldDeclareOneDurableDirectExchange() {
        List<DirectExchange> exchanges = topology.getDeclarablesByType(DirectExchange.class);

        assertThat(exchanges).hasSize(1);
        assertThat(exchanges.get(0).getName()).isEqualTo(InterbankTopology.EXCHANGE);
        assertThat(exchanges.get(0).isDurable()).isTrue();
    }

    @Test
    void shouldDeclareDurableQuorumQueues() {
        List<Queue> queues = topology.getDeclarablesByType(Queue.class);

        assertThat(queues).extracting(Queue::getName).containsExactlyInAnyOrder(
                InterbankTopology.PARTNER_PACS008, InterbankTopology.PARTNER_PACS028, InterbankTopology.BANK_PACS002,
                InterbankTopology.BANK_PACS002_DLQ, InterbankTopology.BANK_PACS008, InterbankTopology.BANK_PACS028,
                InterbankTopology.PARTNER_PACS002, InterbankTopology.BANK_PACS008_DLQ,
                InterbankTopology.BANK_PACS028_DLQ);
        assertThat(queues).extracting(Queue::getName).containsExactlyElementsOf(InterbankTopology.queues());
        assertThat(queues).allSatisfy(queue -> {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum");
        });
    }

    @Test
    void shouldPairEveryDlqWithQueueOfSameBank() {
        for (String queue : InterbankTopology.queues()) {
            if (queue.endsWith(".dlq")) {
                assertThat(InterbankTopology.queues()).as("the queue %s is the dead letters of", queue)
                        .contains(queue.substring(0, queue.length() - ".dlq".length()));
            }
        }
    }

    @Test
    void shouldBindEveryQueueByItsName() {
        List<Binding> bindings = topology.getDeclarablesByType(Binding.class);

        assertThat(bindings).hasSize(InterbankTopology.queues().size());
        assertThat(bindings).allSatisfy(binding -> {
            assertThat(binding.getExchange()).isEqualTo(InterbankTopology.EXCHANGE);
            assertThat(binding.getRoutingKey()).isEqualTo(binding.getDestination());
        });
    }
}
