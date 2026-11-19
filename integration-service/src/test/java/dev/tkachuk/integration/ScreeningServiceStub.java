package dev.tkachuk.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** WireMock in place of the screening service, with the same stubs compose.yaml serves. */
@TestConfiguration(proxyBeanMethods = false)
public class ScreeningServiceStub {

    static final String PATH = "/ws/screening";

    @Bean(destroyMethod = "stop")
    WireMockServer screeningService() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort()
                .usingFilesUnderClasspath("sanctions-screening"));
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar screeningServiceUrl(WireMockServer screeningService) {
        return registry -> registry.add("sanctions-screening.url", () -> screeningService.baseUrl() + PATH);
    }

    @Bean
    TestScreeningService testScreeningService(WireMockServer screeningService, CircuitBreaker breaker) {
        return new TestScreeningService(screeningService, breaker);
    }
}
