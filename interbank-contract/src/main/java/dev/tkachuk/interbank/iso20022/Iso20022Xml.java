package dev.tkachuk.interbank.iso20022;

import java.io.IOException;
import java.io.StringReader;
import java.util.Iterator;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Reads one ISO 20022 document that came from outside. The parser refuses any DOCTYPE, which rules out
 * external entities (XXE: reading local files or calling internal URLs) and entity expansion attacks.
 */
final class Iso20022Xml {

    private static final String PREFIX = "d";

    private final Document document;
    private final String namespace;
    private final String messageName;

    private Iso20022Xml(Document document, String namespace, String messageName) {
        this.document = document;
        this.namespace = namespace;
        this.messageName = messageName;
    }

    /**
     * @param namespace   the message's XML namespace, e.g. "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"
     * @param messageName for error messages only, e.g. "pacs.008"
     */
    static Iso20022Xml parse(String xml, String namespace, String messageName) {
        if (xml == null || xml.isBlank()) {
            throw new InvalidMessageException("The " + messageName + " message is empty");
        }
        Document document;
        try {
            document = newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (SAXException | IOException e) {
            throw new InvalidMessageException("The " + messageName + " message is not well-formed XML: " + e.getMessage(), e);
        }
        String actual = document.getDocumentElement().getNamespaceURI();
        if (!namespace.equals(actual)) {
            throw new InvalidMessageException(
                    "Expected a " + messageName + " document in namespace " + namespace + ", got " + actual);
        }
        return new Iso20022Xml(document, namespace, messageName);
    }

    /**
     * The trimmed text at a path below the Document element, e.g. "FIToFICstmrCdtTrf/GrpHdr/MsgId", or an
     * attribute as the last step, e.g. ".../IntrBkSttlmAmt/@Ccy".
     *
     * @throws InvalidMessageException when the element is missing or empty
     */
    String required(String path) {
        String value = text(path);
        if (value.isEmpty()) {
            throw new InvalidMessageException("The " + messageName + " message has no " + path);
        }
        return value;
    }

    private String text(String path) {
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new SingleNamespace(namespace));
        try {
            return xpath.evaluate(toXPath(path), document).strip();
        } catch (XPathExpressionException e) {
            // The paths are constants that the round-trip tests read, so a failure here is the document's doing.
            throw new InvalidMessageException("Cannot read " + path + " from the " + messageName + " message", e);
        }
    }

    /** "A/B/@C" becomes "/d:Document/d:A/d:B/@C": every element step is in the message's namespace. */
    private static String toXPath(String path) {
        StringBuilder expression = new StringBuilder("/" + PREFIX + ":Document");
        for (String step : path.split("/")) {
            expression.append('/').append(step.startsWith("@") ? step : PREFIX + ":" + step);
        }
        return expression.toString();
    }

    private static DocumentBuilder newDocumentBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ThrowingErrorHandler());
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("This JDK's XML parser cannot be configured securely", e);
        }
    }

    /** Fails on the first error instead of printing "[Fatal Error]" lines to stderr. */
    private static final class ThrowingErrorHandler implements ErrorHandler {

        @Override
        public void warning(SAXParseException exception) {
            // Warnings never make a message invalid.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }

    private record SingleNamespace(String uri) implements NamespaceContext {

        @Override
        public String getNamespaceURI(String prefix) {
            return PREFIX.equals(prefix) ? uri : XMLConstants.NULL_NS_URI;
        }

        @Override
        public String getPrefix(String namespaceUri) {
            return uri.equals(namespaceUri) ? PREFIX : null;
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceUri) {
            return uri.equals(namespaceUri) ? List.of(PREFIX).iterator() : List.<String>of().iterator();
        }
    }
}
