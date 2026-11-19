package dev.tkachuk.integration.screening;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.integration.screening.soap.ScreenPaymentRequest;
import dev.tkachuk.integration.screening.soap.ScreenPaymentResponse;
import jakarta.xml.bind.JAXBContext;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class ScreeningContractTest {

    private static final String WSDL = "wsdl/sanctions-screening.wsdl";
    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String WSDL_SOAP_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    @Test
    void shouldUseWsdlSoapAction() throws Exception {
        Element operation = only(read(WSDL), WSDL_SOAP_NS, "operation");

        assertThat(operation.getAttribute("soapAction")).isEqualTo(SoapScreeningService.SOAP_ACTION);
    }

    @Test
    void shouldBeSoap11DocumentLiteralOverHttp() throws Exception {
        Document wsdl = read(WSDL);
        Element binding = only(wsdl, WSDL_SOAP_NS, "binding");

        assertThat(binding.getAttribute("style")).isEqualTo("document");
        assertThat(binding.getAttribute("transport")).isEqualTo("http://schemas.xmlsoap.org/soap/http");
        NodeList bodies = wsdl.getElementsByTagNameNS(WSDL_SOAP_NS, "body");
        assertThat(bodies.getLength()).isEqualTo(2);
        for (int i = 0; i < bodies.getLength(); i++) {
            assertThat(((Element) bodies.item(i)).getAttribute("use")).isEqualTo("literal");
        }
    }

    @Test
    void shouldMatchWsdlMessagesToGeneratedClasses() throws Exception {
        Document wsdl = read(WSDL);
        String namespace = wsdl.getDocumentElement().getAttribute("targetNamespace");
        List<String> parts = new ArrayList<>();
        NodeList partNodes = wsdl.getElementsByTagNameNS(WSDL_NS, "part");
        for (int i = 0; i < partNodes.getLength(); i++) {
            parts.add(((Element) partNodes.item(i)).getAttribute("element"));
        }
        Element imported = only(wsdl, XSD_NS, "import");
        Document schema = read("wsdl/" + imported.getAttribute("schemaLocation"));

        assertThat(parts).containsExactly("tns:ScreenPaymentRequest", "tns:ScreenPaymentResponse");
        assertThat(imported.getAttribute("namespace")).isEqualTo(namespace);
        assertThat(schema.getDocumentElement().getAttribute("targetNamespace")).isEqualTo(namespace);
        assertThat(elementNameOf(new ScreenPaymentRequest())).isEqualTo(new QName(namespace, "ScreenPaymentRequest"));
        assertThat(elementNameOf(new ScreenPaymentResponse()))
                .isEqualTo(new QName(namespace, "ScreenPaymentResponse"));
    }

    @Test
    void shouldImportSchemaTheClientValidates() throws Exception {
        Element imported = only(read(WSDL), XSD_NS, "import");

        assertThat(resourcePath("wsdl/" + imported.getAttribute("schemaLocation")))
                .isEqualTo(ScreeningConfiguration.SCHEMA);
    }

    private static QName elementNameOf(Object message) throws Exception {
        return JAXBContext.newInstance(message.getClass()).createJAXBIntrospector().getElementName(message);
    }

    private static Element only(Document document, String namespace, String name) {
        NodeList nodes = document.getElementsByTagNameNS(namespace, name);
        assertThat(nodes.getLength()).as("<" + name + "> elements").isEqualTo(1);
        return (Element) nodes.item(0);
    }

    private static String resourcePath(String path) {
        return Path.of(path).normalize().toString().replace('\\', '/');
    }

    private static Document read(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        String resource = resourcePath(path);
        try (InputStream in = ScreeningContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return factory.newDocumentBuilder().parse(in);
        }
    }
}
