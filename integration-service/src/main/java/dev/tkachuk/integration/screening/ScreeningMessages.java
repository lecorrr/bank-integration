package dev.tkachuk.integration.screening;

import dev.tkachuk.integration.screening.soap.Direction;
import dev.tkachuk.integration.screening.soap.Party;
import dev.tkachuk.integration.screening.soap.PartyRole;
import dev.tkachuk.integration.screening.soap.ScreenPaymentRequest;
import dev.tkachuk.integration.screening.soap.ScreenPaymentResponse;
import dev.tkachuk.interbank.iso20022.Pacs008;
import java.time.Instant;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;

/** Maps a payment to a screening request and reads the response. Kept apart from the SOAP call for testing. */
final class ScreeningMessages {

    static final String SOAP_11_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";

    private ScreeningMessages() {
    }

    static ScreenPaymentRequest requestFor(Pacs008 payment, Direction direction) {
        ScreenPaymentRequest request = new ScreenPaymentRequest();
        request.setPaymentReference(payment.uetr());
        request.setDirection(direction);
        request.getParty().add(party(PartyRole.DEBTOR, payment.debtorName(), payment.debtorIban(),
                payment.debtorAgentBic()));
        request.getParty().add(party(PartyRole.CREDITOR, payment.creditorName(), payment.creditorIban(),
                payment.creditorAgentBic()));
        request.setRemittanceInformation(payment.remittanceInformation());
        return request;
    }

    static Screening screeningOf(ScreenPaymentRequest request, ScreenPaymentResponse response, Instant answeredAt) {
        String asked = request.getPaymentReference();
        if (!asked.equals(response.getPaymentReference())) {
            throw new ScreeningUnavailableException("Screening response is for payment "
                    + response.getPaymentReference() + ", expected " + asked);
        }
        if (response.getOutcome() == null || response.getScreeningId() == null) {
            throw new ScreeningUnavailableException("Screening response for payment " + asked
                    + " has no outcome or no screening id");
        }
        return switch (response.getOutcome()) {
            case CLEAR -> {
                if (!response.getMatch().isEmpty()) {
                    throw new ScreeningUnavailableException("Screening response for payment " + asked
                            + " is CLEAR but lists matches");
                }
                yield new Screening(Screening.Outcome.CLEAR, response.getScreeningId(), answeredAt);
            }
            case HIT -> new Screening(Screening.Outcome.HIT, response.getScreeningId(), answeredAt);
        };
    }

    static String matchesOf(ScreenPaymentResponse response) {
        if (response.getMatch().isEmpty()) {
            return "no match details";
        }
        return response.getMatch().stream()
                .map(match -> match.getField() + " on " + match.getListName())
                .collect(Collectors.joining(", "));
    }

    static boolean isClientFault(QName faultCode) {
        if (faultCode == null || !SOAP_11_ENVELOPE.equals(faultCode.getNamespaceURI())) {
            return false;
        }
        String code = faultCode.getLocalPart();
        return code.equals("Client") || code.startsWith("Client.");
    }

    private static Party party(PartyRole role, String name, String iban, String bic) {
        Party party = new Party();
        party.setRole(role);
        party.setName(name);
        party.setIban(iban);
        party.setBic(bic);
        return party;
    }
}
