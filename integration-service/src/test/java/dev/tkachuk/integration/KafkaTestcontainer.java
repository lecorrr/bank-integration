package dev.tkachuk.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A real Kafka in Docker for integration tests. Camel's Kafka component reads its own property rather than
 * Spring's Kafka settings, so the broker's address is handed to it here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainer {

    @Bean
    KafkaContainer kafka() {
        return new KafkaContainer(TestImages.fromSystemProperty("kafka.image"));
    }

    @Bean
    DynamicPropertyRegistrar kafkaBrokers(KafkaContainer kafka) {
        return registry -> registry.add("camel.component.kafka.brokers", kafka::getBootstrapServers);
    }
}
