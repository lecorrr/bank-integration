package dev.tkachuk.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The whole service against a real PostgreSQL, RabbitMQ and Kafka. Every test class with this annotation has the
 * same configuration, so Spring gives them one context, and the containers start once for all of them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        // Short waits, so that a late answer is asked about well within a test's timeout.
        "status-requests.first-after=2s",
        "status-requests.max-interval=2s",
        "status-requests.poll-interval=500ms"})
@Import({PostgresTestcontainer.class, RabbitTestcontainer.class, KafkaTestcontainer.class})
public @interface ServiceIntegrationTest {
}
