package dev.tkachuk.integration.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.integration.incoming.TestPayments;
import dev.tkachuk.integration.screening.soap.Direction;
import dev.tkachuk.integration.screening.soap.Match;
import dev.tkachuk.integration.screening.soap.Outcome;
import dev.tkachuk.integration.screening.soap.Party;
import dev.tkachuk.integration.screening.soap.PartyRole;
import dev.tkachuk.integration.screening.soap.ScreenPaymentRequest;
import dev.tkachuk.integration.screening.soap.ScreenPaymentResponse;
import dev.tkachuk.integration.screening.soap.ScreenedField;
import dev.tkachuk.interbank.iso20022.Pacs008;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.time.Instant;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScreeningMessagesTest {

    private static final Instant ANSWERED_AT = Instant.parse("2026-11-19T09:00:00Z");

    private final Pacs008 payment = TestPayments.payment();

    @Test
    void shouldSendPayerPayeeAndRemittanceInformation() {
        ScreenPaymentRequest request = ScreeningMessages.requestFor(payment, Direction.INCOMING);

        assertThat(request.getPaymentReference()).isEqualTo(payment.uetr());
        assertThat(request.getDirection()).isEqualTo(Direction.INCOMING);
        assertThat(request.getParty()).hasSize(2);
        Party payer = request.getParty().get(0);
        assertThat(payer.getRole()).isEqualTo(PartyRole.DEBTOR);
        assertThat(payer.getName()).isEqualTo(payment.debtorName());
        assertThat(payer.getIban()).isEqualTo(payment.debtorIban());
        assertThat(payer.getBic()).isEqualTo(payment.debtorAgentBic());
        Party payee = request.getParty().get(1);
        assertThat(payee.getRole()).isEqualTo(PartyRole.CREDITOR);
        assertThat(payee.getName()).isEqualTo(payment.creditorName());
        assertThat(payee.getIban()).isEqualTo(payment.creditorIban());
        assertThat(payee.getBic()).isEqualTo(payment.creditorAgentBic());
        assertThat(request.getRemittanceInformation()).isEqualTo(payment.remittanceInformation());
    }

    @Test
    void shouldBuildRequestThatPassesSchema() throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(ScreenPaymentRequest.class).createMarshaller();
        marshaller.setSchema(SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(getClass().getClassLoader().getResource(ScreeningConfiguration.SCHEMA)));
        StringWriter xml = new StringWriter();

        marshaller.marshal(ScreeningMessages.requestFor(payment, Direction.OUTGOING), xml);

        assertThat(xml.toString()).contains(payment.uetr()).contains("OUTGOING");
    }

    @Test
    void shouldReadClearResponse() {
        ScreenPaymentRequest request = ScreeningMessages.requestFor(payment, Direction.OUTGOING);

        Screening screening =
                ScreeningMessages.screeningOf(request, answer(payment.uetr(), Outcome.CLEAR), ANSWERED_AT);

        assertThat(screening).isEqualTo(new Screening(Screening.Outcome.CLEAR, "SCR-1", ANSWERED_AT));
        assertThat(screening.cleared()).isTrue();
    }

    @Test
    void shouldReadHitWithOrWithoutMatches() {
        ScreenPaymentRequest request = ScreeningMessages.requestFor(payment, Direction.OUTGOING);
        ScreenPaymentResponse saying = answer(payment.uetr(), Outcome.HIT, match(ScreenedField.CREDITOR, "UA-NSDC"));
        ScreenPaymentResponse notSaying = answer(payment.uetr(), Outcome.HIT);

        assertThat(ScreeningMessages.screeningOf(request, saying, ANSWERED_AT).cleared()).isFalse();
        assertThat(ScreeningMessages.screeningOf(request, notSaying, ANSWERED_AT).cleared()).isFalse();
    }

    @Test
    void shouldRejectResponseForAnotherPayment() {
        ScreenPaymentRequest request = ScreeningMessages.requestFor(payment, Direction.OUTGOING);
        String another = "9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f";

        assertThatThrownBy(() -> ScreeningMessages.screeningOf(request, answer(another, Outcome.CLEAR), ANSWERED_AT))
                .isInstanceOf(ScreeningUnavailableException.class)
                .hasMessageContaining(another)
                .hasMessageContaining(payment.uetr());
    }

    @Test
    void shouldRejectClearResponseWithMatches() {
        ScreenPaymentRequest request = ScreeningMessages.requestFor(payment, Direction.OUTGOING);
        ScreenPaymentResponse contradiction =
                answer(payment.uetr(), Outcome.CLEAR, match(ScreenedField.DEBTOR, "UA-NSDC"));

        assertThatThrownBy(() -> ScreeningMessages.screeningOf(request, contradiction, ANSWERED_AT))
                .isInstanceOf(ScreeningUnavailableException.class)
                .hasMessageContaining("CLEAR but lists matches");
    }

    @Test
    void shouldRejectResponseWithoutOutcome() {
        ScreenPaymentRequest request = ScreeningMessages.requestFor(payment, Direction.OUTGOING);
        ScreenPaymentResponse empty = answer(payment.uetr(), null);

        assertThatThrownBy(() -> ScreeningMessages.screeningOf(request, empty, ANSWERED_AT))
                .isInstanceOf(ScreeningUnavailableException.class)
                .hasMessageContaining("no outcome");
    }

    @Test
    void shouldDescribeMatchesWithoutNames() {
        ScreenPaymentResponse hit = answer(payment.uetr(), Outcome.HIT, match(ScreenedField.CREDITOR, "UA-NSDC"),
                match(ScreenedField.REMITTANCE_INFORMATION, "EU"));

        assertThat(ScreeningMessages.matchesOf(hit)).isEqualTo("CREDITOR on UA-NSDC, REMITTANCE_INFORMATION on EU");
        assertThat(ScreeningMessages.matchesOf(answer(payment.uetr(), Outcome.HIT))).isEqualTo("no match details");
    }

    @ParameterizedTest(name = "{0} {1}: client fault {2}")
    @CsvSource({
            "http://schemas.xmlsoap.org/soap/envelope/, Client, true",
            "http://schemas.xmlsoap.org/soap/envelope/, Client.InvalidRequest, true",
            "http://schemas.xmlsoap.org/soap/envelope/, Server, false",
            "http://schemas.xmlsoap.org/soap/envelope/, ClientError, false",
            "http://www.w3.org/2003/05/soap-envelope, Client, false"})
    void shouldTreatOnlySoap11ClientCodeAsClientFault(String namespace, String code, boolean client) {
        assertThat(ScreeningMessages.isClientFault(new QName(namespace, code))).isEqualTo(client);
    }

    @Test
    void shouldNotTreatFaultWithoutCodeAsClientFault() {
        assertThat(ScreeningMessages.isClientFault(null)).isFalse();
    }

    private static ScreenPaymentResponse answer(String reference, Outcome outcome, Match... matches) {
        ScreenPaymentResponse response = new ScreenPaymentResponse();
        response.setPaymentReference(reference);
        response.setScreeningId("SCR-1");
        response.setOutcome(outcome);
        for (Match match : matches) {
            response.getMatch().add(match);
        }
        return response;
    }

    private static Match match(ScreenedField field, String list) {
        Match match = new Match();
        match.setField(field);
        match.setListName(list);
        return match;
    }
}
