package dev.tkachuk.integration.screening;

import dev.tkachuk.integration.screening.soap.ScreenPaymentRequest;
import dev.tkachuk.integration.screening.soap.ScreenPaymentResponse;
import org.springframework.oxm.MarshallingFailureException;
import org.springframework.oxm.XmlMappingException;
import org.springframework.stereotype.Component;
import org.springframework.ws.WebServiceException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.client.core.SoapActionCallback;

/**
 * Calls the screening service over SOAP. A client fault or an invalid request becomes ScreeningRefusedException,
 * since retrying won't help; anything else becomes ScreeningUnavailableException. Messages never include the
 * fault string, which may quote names from the request.
 */
@Component
class SoapScreeningService implements ScreeningService {

    static final String SOAP_ACTION = "urn:bank-integration:sanctions-screening:1:ScreenPayment";

    private final WebServiceTemplate template;

    SoapScreeningService(WebServiceTemplate screeningTemplate) {
        this.template = screeningTemplate;
    }

    @Override
    public ScreenPaymentResponse call(ScreenPaymentRequest request) {
        String payment = request.getPaymentReference();
        Object answer;
        try {
            answer = template.marshalSendAndReceive(request, new SoapActionCallback(SOAP_ACTION));
        } catch (SoapFaultClientException fault) {
            if (ScreeningMessages.isClientFault(fault.getFaultCode())) {
                throw new ScreeningRefusedException("Screening request for payment " + payment
                        + " refused by the service (SOAP fault " + fault.getFaultCode().getLocalPart() + ")");
            }
            throw new ScreeningUnavailableException("Screening service failed for payment " + payment
                    + " (SOAP fault " + codeOf(fault) + ")");
        } catch (MarshallingFailureException e) {
            throw new ScreeningRefusedException("Screening request for payment " + payment
                    + " does not match the schema");
        } catch (XmlMappingException e) {
            throw new ScreeningUnavailableException("Screening response for payment " + payment
                    + " does not match the schema");
        } catch (WebServiceException e) {
            throw new ScreeningUnavailableException("Screening call failed for payment " + payment + ": "
                    + e.getMessage(), e);
        }
        if (answer instanceof ScreenPaymentResponse response) {
            return response;
        }
        throw new ScreeningUnavailableException("Unexpected screening response for payment " + payment + ": "
                + (answer == null ? "empty body" : answer.getClass().getSimpleName()));
    }

    private static String codeOf(SoapFaultClientException fault) {
        return fault.getFaultCode() == null ? "without a code" : fault.getFaultCode().getLocalPart();
    }
}
