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
    void declaresOneDurableDirectExchange() {
        List<DirectExchange> exchanges = topology.getDeclarablesByType(DirectExchange.class);

        assertThat(exchanges).hasSize(1);
        assertThat(exchanges.get(0).getName()).isEqualTo(InterbankTopology.EXCHANGE);
        assertThat(exchanges.get(0).isDurable()).isTrue();
    }

    @Test
    void everyQueueIsADurableQuorumQueue() {
        List<Queue> queues = topology.getDeclarablesByType(Queue.class);

        assertThat(queues).extracting(Queue::getName)
                .containsExactlyInAnyOrder(InterbankTopology.PARTNER_PACS008, InterbankTopology.BANK_PACS002);
        assertThat(queues).allSatisfy(queue -> {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum");
        });
    }

    @Test
    void everyQueueIsBoundToTheExchangeByItsOwnName() {
        List<Binding> bindings = topology.getDeclarablesByType(Binding.class);

        assertThat(bindings).hasSize(2);
        assertThat(bindings).allSatisfy(binding -> {
            assertThat(binding.getExchange()).isEqualTo(InterbankTopology.EXCHANGE);
            assertThat(binding.getRoutingKey()).isEqualTo(binding.getDestination());
        });
    }
}
