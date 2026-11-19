package dev.tkachuk.integration.screening;

import dev.tkachuk.integration.screening.ScreeningProperties.CircuitBreakerSettings;
import dev.tkachuk.integration.screening.soap.ScreenPaymentRequest;
import dev.tkachuk.integration.screening.soap.ScreenPaymentResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

/** Spring WS client for the screening service, and the circuit breaker in front of it. */
@Configuration(proxyBeanMethods = false)
class ScreeningConfiguration {

    static final String SCHEMA = "xsd/sanctions-screening.xsd";

    private static final Logger log = LoggerFactory.getLogger(ScreeningConfiguration.class);

    @Bean
    Jaxb2Marshaller screeningMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setClassesToBeBound(ScreenPaymentRequest.class, ScreenPaymentResponse.class);
        // Requests and responses are validated against the XSD.
        marshaller.setSchema(new ClassPathResource(SCHEMA));
        return marshaller;
    }

    @Bean
    WebServiceTemplate screeningTemplate(Jaxb2Marshaller screeningMarshaller, ScreeningProperties properties) {
        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(properties.connectTimeout());
        sender.setReadTimeout(properties.readTimeout());
        WebServiceTemplate template = new WebServiceTemplate(screeningMarshaller);
        template.setMessageSender(sender);
        template.setDefaultUri(properties.url());
        return template;
    }

    @Bean
    CircuitBreaker sanctionsScreeningBreaker(ScreeningProperties properties) {
        return circuitBreaker(properties.circuitBreaker());
    }

    static CircuitBreaker circuitBreaker(CircuitBreakerSettings settings) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .waitDurationInOpenState(settings.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
                .recordExceptions(ScreeningUnavailableException.class)
                .ignoreExceptions(ScreeningRefusedException.class)
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("sanctions-screening", config);
        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.StateTransition transition = event.getStateTransition();
            switch (transition.getToState()) {
                case OPEN -> log.warn("Sanctions screening circuit breaker opened: no calls for {}, "
                        + "then {} trial calls", settings.waitInOpenState(), settings.permittedCallsInHalfOpenState());
                case HALF_OPEN -> log.info("Sanctions screening circuit breaker half-open, trying the service again");
                case CLOSED -> log.info("Sanctions screening circuit breaker closed");
                default -> log.info("Sanctions screening circuit breaker: {} -> {}",
                        transition.getFromState(), transition.getToState());
            }
        });
        return breaker;
    }
}
