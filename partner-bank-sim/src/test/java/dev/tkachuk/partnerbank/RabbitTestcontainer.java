package dev.tkachuk.partnerbank;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestcontainer {

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitmq() {
        String image = System.getProperty("rabbitmq.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("System property rabbitmq.image is not set. Run the tests through Maven "
                    + "(./mvnw verify), or pass -Drabbitmq.image with the tag from pom.xml.");
        }
        return new RabbitMQContainer(image);
    }
}
