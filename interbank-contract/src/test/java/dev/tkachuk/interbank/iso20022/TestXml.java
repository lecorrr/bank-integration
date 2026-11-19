package dev.tkachuk.interbank.iso20022;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** Checks our XML with plain JDK tools, separately from Iso20022Xml, so a bug there can't hide a bug here. */
final class TestXml {

    private TestXml() {
    }

    static void validate(String xml, String messageName) throws Exception {
        SchemaFactory.newDefaultInstance()
                .newSchema(TestXml.class.getResource("/xsd/" + messageName + ".xsd"))
                .newValidator()
                .validate(new StreamSource(new StringReader(xml)));
    }

    static String text(String xml, String path) throws Exception {
        StringBuilder expression = new StringBuilder("/*");
        for (String step : path.split("/")) {
            expression.append("/*[local-name()='").append(step).append("']");
        }
        return XPathFactory.newDefaultInstance().newXPath()
                .evaluate(expression.toString(), document(xml));
    }

    static String rootNamespace(String xml) throws Exception {
        return document(xml).getDocumentElement().getNamespaceURI();
    }

    private static Document document(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultNSInstance();
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
}
