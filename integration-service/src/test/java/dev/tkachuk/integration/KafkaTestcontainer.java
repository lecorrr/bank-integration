package dev.tkachuk.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;

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
