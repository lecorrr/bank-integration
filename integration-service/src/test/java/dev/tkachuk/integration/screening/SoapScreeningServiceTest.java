package dev.tkachuk.integration.screening;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingXPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.tkachuk.integration.incoming.TestPayments;
import dev.tkachuk.integration.screening.ScreeningProperties.CircuitBreakerSettings;
import dev.tkachuk.integration.screening.soap.Direction;
import dev.tkachuk.integration.screening.soap.Match;
import dev.tkachuk.integration.screening.soap.Outcome;
import dev.tkachuk.integration.screening.soap.ScreenPaymentRequest;
import dev.tkachuk.integration.screening.soap.ScreenPaymentResponse;
import dev.tkachuk.integration.screening.soap.ScreenedField;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;

class SoapScreeningServiceTest {

    private static final String PATH = "/ws/screening";
    private static final String LISTED = "Sanctioned Test Entity";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final String FAULT_STRING = "Name Olena Kovalenko is not accepted";

    private WireMockServer server;
    private SoapScreeningService service;

    @BeforeEach
    void startTheStandIn() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort()
                .usingFilesUnderClasspath("sanctions-screening"));
        server.start();
        service = serviceAt(server.baseUrl() + PATH);
    }

    @AfterEach
    void stopTheStandIn() {
        server.stop();
    }

    @Test
    void shouldReturnClearForUnlistedNames() {
        ScreenPaymentRequest request = requestFor(TestPayments.payment());

        ScreenPaymentResponse response = service.call(request);

        assertThat(response.getOutcome()).isEqualTo(Outcome.CLEAR);
        assertThat(response.getPaymentReference()).isEqualTo(request.getPaymentReference());
        assertThat(response.getScreeningId()).startsWith("SCR-");
        assertThat(response.getMatch()).isEmpty();
        server.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("SOAPAction", containing(SoapScreeningService.SOAP_ACTION))
                .withRequestBody(matchingXPath(
                        "/Envelope/Body/ScreenPaymentRequest[Direction='OUTGOING'][count(Party)=2]")));
    }

    @Test
    void shouldReturnHitOnDebtorForListedPayer() {
        ScreenPaymentResponse response = service.call(requestFor(TestPayments.another().debtorName(LISTED).build()));

        assertThat(response.getOutcome()).isEqualTo(Outcome.HIT);
        assertMatch(response, ScreenedField.DEBTOR);
    }

    @Test
    void shouldReturnHitOnCreditorForListedPayee() {
        ScreenPaymentResponse response =
                service.call(requestFor(TestPayments.another().creditorName(LISTED).build()));

        assertThat(response.getOutcome()).isEqualTo(Outcome.HIT);
        assertMatch(response, ScreenedField.CREDITOR);
    }

    @Test
    void shouldReturnHitForListedNameInRemittanceInformation() {
        ScreenPaymentResponse response = service.call(requestFor(
                TestPayments.another().remittanceInformation("Invoice 17 for " + LISTED).build()));

        assertThat(response.getOutcome()).isEqualTo(Outcome.HIT);
        assertMatch(response, ScreenedField.REMITTANCE_INFORMATION);
    }

    @Test
    void shouldThrowRefusedOnClientFault() {
        answerEverything(fault("soapenv:Client"));

        assertThatThrownBy(() -> service.call(requestFor(TestPayments.payment())))
                .isInstanceOf(ScreeningRefusedException.class)
                .hasMessageContaining("Client")
                .hasMessageNotContaining("Olena");
    }

    @Test
    void shouldThrowUnavailableOnServerFault() {
        answerEverything(fault("soapenv:Server"));

        assertThatThrownBy(() -> service.call(requestFor(TestPayments.payment())))
                .isInstanceOf(ScreeningUnavailableException.class)
                .hasMessageContaining("Server")
                .hasMessageNotContaining("Olena");
    }

    @Test
    void shouldThrowUnavailableOnHttpError() {
        answerEverything(aResponse().withStatus(503).withHeader("Content-Type", "text/plain")
                .withBody("Service Unavailable"));

        assertThatThrownBy(() -> service.call(requestFor(TestPayments.payment())))
                .isInstanceOf(ScreeningUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    void shouldThrowUnavailableOnReadTimeout() {
        answerEverything(aResponse().withStatus(200).withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(answer("CLEAR")).withFixedDelay((int) READ_TIMEOUT.toMillis() * 3));

        assertThatThrownBy(() -> service.call(requestFor(TestPayments.payment())))
                .isInstanceOf(ScreeningUnavailableException.class)
                .hasMessageContaining("call failed");
    }

    @Test
    void shouldThrowUnavailableWhenServiceIsDown() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SoapScreeningService nowhere = serviceAt("http://localhost:" + closedPort + PATH);

        assertThatThrownBy(() -> nowhere.call(requestFor(TestPayments.payment())))
                .isInstanceOf(ScreeningUnavailableException.class)
                .hasMessageContaining("call failed");
    }

    @Test
    void shouldThrowUnavailableOnInvalidResponse() {
        answerEverything(aResponse().withStatus(200).withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(answer("MAYBE")));

        assertThatThrownBy(() -> service.call(requestFor(TestPayments.payment())))
                .isInstanceOf(ScreeningUnavailableException.class)
                .hasMessageContaining("does not match the schema")
                .hasMessageNotContaining("MAYBE");
    }

    @Test
    void shouldThrowUnavailableOnNonSoapResponse() {
        answerEverything(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                .withBody("<html><body>Gateway login</body></html>"));

        assertThatThrownBy(() -> service.call(requestFor(TestPayments.payment())))
                .isInstanceOf(ScreeningUnavailableException.class);
    }

    private static SoapScreeningService serviceAt(String url) throws Exception {
        ScreeningConfiguration configuration = new ScreeningConfiguration();
        Jaxb2Marshaller marshaller = configuration.screeningMarshaller();
        marshaller.afterPropertiesSet();
        ScreeningProperties properties = new ScreeningProperties(url, Duration.ofSeconds(2), READ_TIMEOUT,
                new CircuitBreakerSettings(50, 10, 5, Duration.ofSeconds(30), 2));
        return new SoapScreeningService(configuration.screeningTemplate(marshaller, properties));
    }

    private static ScreenPaymentRequest requestFor(Pacs008 payment) {
        return ScreeningMessages.requestFor(payment, Direction.OUTGOING);
    }

    private void answerEverything(ResponseDefinitionBuilder response) {
        server.stubFor(post(urlEqualTo(PATH)).atPriority(1).willReturn(response));
    }

    private static ResponseDefinitionBuilder fault(String code) {
        return aResponse().withStatus(500).withHeader("Content-Type", "text/xml; charset=utf-8").withBody("""
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                    <soapenv:Body>
                        <soapenv:Fault>
                            <faultcode>%s</faultcode>
                            <faultstring>%s</faultstring>
                        </soapenv:Fault>
                    </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(code, FAULT_STRING));
    }

    private static String answer(String outcome) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                    <soapenv:Body>
                        <ScreenPaymentResponse xmlns="urn:bank-integration:sanctions-screening:1">
                            <PaymentReference>%s</PaymentReference>
                            <ScreeningId>SCR-TEST</ScreeningId>
                            <Outcome>%s</Outcome>
                        </ScreenPaymentResponse>
                    </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(TestPayments.UETR, outcome);
    }

    private static void assertMatch(ScreenPaymentResponse response, ScreenedField field) {
        assertThat(response.getMatch()).hasSize(1);
        Match match = response.getMatch().getFirst();
        assertThat(match.getField()).isEqualTo(field);
        assertThat(match.getListName()).isEqualTo("UA-NSDC");
    }
}
