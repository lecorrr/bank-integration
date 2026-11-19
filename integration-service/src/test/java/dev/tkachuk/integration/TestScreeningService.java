package dev.tkachuk.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/** Lets a test take the screening service down or make it refuse everything, then bring it back. */
public final class TestScreeningService {

    public static final String LISTED_NAME = "Sanctioned Test Entity";

    private final WireMockServer server;
    private final CircuitBreaker breaker;

    TestScreeningService(WireMockServer server, CircuitBreaker breaker) {
        this.server = server;
        this.breaker = breaker;
    }

    public void goDown() {
        answerEverything(aResponse().withStatus(503).withHeader("Content-Type", "text/plain")
                .withBody("Service Unavailable"));
    }

    public void refuseEverything() {
        answerEverything(aResponse().withStatus(500).withHeader("Content-Type", "text/xml; charset=utf-8").withBody("""
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                    <soapenv:Body>
                        <soapenv:Fault>
                            <faultcode>soapenv:Client</faultcode>
                            <faultstring>The request is not accepted</faultstring>
                        </soapenv:Fault>
                    </soapenv:Body>
                </soapenv:Envelope>
                """));
    }

    public void comeBack() {
        server.resetToDefaultMappings();
    }

    public CircuitBreaker.State breakerState() {
        return breaker.getState();
    }

    public void reset() {
        server.resetToDefaultMappings();
        breaker.reset();
    }

    private void answerEverything(ResponseDefinitionBuilder response) {
        // Priority 1 wins over the stubs loaded from files.
        server.stubFor(post(urlEqualTo(ScreeningServiceStub.PATH)).atPriority(1).willReturn(response));
    }
}
