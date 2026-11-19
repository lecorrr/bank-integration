package dev.tkachuk.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Starts the service against Testcontainers and the WireMock screening service, with short timings. All the flow
 * tests share this one context, so they share its containers too.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "status-requests.first-after=2s",
        "status-requests.max-interval=2s",
        "status-requests.poll-interval=500ms",
        "sanctions-screening.circuit-breaker.sliding-window-size=4",
        "sanctions-screening.circuit-breaker.minimum-number-of-calls=2",
        "sanctions-screening.circuit-breaker.wait-in-open-state=1s",
        "sanctions-screening.circuit-breaker.permitted-calls-in-half-open-state=1",
        "reconciliation.poll-interval=500ms"})
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RabbitTestcontainer.class, KafkaTestcontainer.class,
        ScreeningServiceStub.class, SftpTestcontainer.class})
public @interface ServiceIntegrationTest {
}
