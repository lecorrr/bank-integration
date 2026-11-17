package dev.tkachuk.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** A real RabbitMQ in Docker for integration tests; Spring points the connection factory at it automatically. */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestcontainer {

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitmq() {
        return new RabbitMQContainer(TestImages.fromSystemProperty("rabbitmq.image"));
    }
}
