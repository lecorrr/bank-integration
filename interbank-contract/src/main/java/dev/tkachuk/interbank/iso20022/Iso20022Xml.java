package dev.tkachuk.interbank.iso20022;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * Writes and reads one ISO 20022 message type with the JAXB classes generated from its official XSD, which is
 * bundled as /xsd/{message name}.xsd. Every document is checked against that XSD both ways: we never send what
 * the other bank's own check would refuse, and never act on a message the standard does not allow.
 *
 * <p>Incoming XML is untrusted. The parser refuses any DOCTYPE, which rules out external entities (XXE:
 * reading local files, calling internal URLs) and entity expansion, and the check uses the bundled XSD only,
 * never one that a document points to. Errors give the element's path and the rule it broke but never its
 * value, because names and IBANs are personal data and end up in logs.
 *
 * @param <D> the generated Document class of the message
 */
final class Iso20022Xml<D> {

    /** The schema validator starts each error with the rule's name, e.g. "cvc-pattern-valid: Value '...'". */
    private static final Pattern RULE = Pattern.compile("cvc-[\\w.-]+(?=:)");

    private final String messageName;
    private final String namespace;
    private final Class<D> documentType;
    private final Function<D, JAXBElement<D>> asRootElement;
    private final JAXBContext context;
    private final Schema schema;

    /**
     * @param messageName   e.g. "pacs.008.001.08"; it also names the XSD
     * @param asRootElement wraps a Document in the Document element (the generated ObjectFactory's
     *                      createDocument): the XSD makes Document a named type, so the class has no
     *                      {@code @XmlRootElement} of its own
     */
    Iso20022Xml(String messageName, String namespace, Class<D> documentType,
            Function<D, JAXBElement<D>> asRootElement) {
        this.messageName = messageName;
        this.namespace = namespace;
        this.documentType = documentType;
        this.asRootElement = asRootElement;
        this.context = newContext(documentType);
        this.schema = loadSchema("/xsd/" + messageName + ".xsd");
    }

    /** @throws InvalidMessageException when the document breaks the schema, e.g. an amount with six decimals */
    String write(D document) {
        StringWriter writer = new StringWriter();
        try {
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.marshal(asRootElement.apply(document), writer);
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot write a " + messageName + " message", e);
        }
        String xml = writer.toString();
        check(xml);
        return xml;
    }

    /**
     * @throws InvalidMessageException when the XML is empty, not well-formed, another message type, or breaks
     *                                 the schema
     */
    D read(String xml) {
        check(xml);
        try {
            SAXSource source = new SAXSource(newSecureParser(), new InputSource(new StringReader(xml)));
            return context.createUnmarshaller().unmarshal(source, documentType).getValue();
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot bind a " + messageName + " message that its schema accepts", e);
        }
    }

    /** An element the schema leaves optional and our scheme requires, such as the UETR. */
    <T> T required(T value, String path) {
        if (value == null) {
            throw new InvalidMessageException("The " + messageName + " message has no " + path);
        }
        return value;
    }

    /** An element the schema lets repeat and our scheme allows exactly once. */
    <T> T single(List<T> values, String path) {
        if (values.size() != 1) {
            throw new InvalidMessageException(
                    "The " + messageName + " message must have exactly one " + path + ", not " + values.size());
        }
        return values.getFirst();
    }

    private void check(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new InvalidMessageException("The " + messageName + " message is empty");
        }
        ElementPath path = new ElementPath(newSecureParser());
        Validator validator = newValidator(path);
        try {
            validator.validate(new SAXSource(path, new InputSource(new StringReader(xml))));
        } catch (SAXException | IOException e) {
            // ElementPath and Refusal throw InvalidMessageException for everything they see; this is the rest.
            throw new InvalidMessageException("The " + messageName + " message cannot be read", e);
        }
    }

    private Validator newValidator(ElementPath path) {
        Validator validator = schema.newValidator();
        try {
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException e) {
            throw new IllegalStateException("This JDK's schema validator cannot be configured securely", e);
        }
        validator.setErrorHandler(new Refusal(path));
        return validator;
    }

    private static JAXBContext newContext(Class<?> documentType) {
        try {
            return JAXBContext.newInstance(documentType);
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot set up JAXB for " + documentType.getName(), e);
        }
    }

    /**
     * Opened through the class loader's URL, from a directory or a jar alike; the factory only reads the bytes
     * and uses the URL to name the file in its errors.
     */
    private static Schema loadSchema(String resource) {
        URL url = Iso20022Xml.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("The schema " + resource + " is not on the classpath");
        }
        try (InputStream xsd = url.openStream()) {
            SchemaFactory factory = SchemaFactory.newDefaultInstance();
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newSchema(new StreamSource(xsd, url.toExternalForm()));
        } catch (IOException | SAXException e) {
            throw new IllegalStateException("Cannot load the schema " + resource, e);
        }
    }

    private static XMLReader newSecureParser() {
        try {
            SAXParserFactory factory = SAXParserFactory.newDefaultNSInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            return factory.newSAXParser().getXMLReader();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("This JDK's XML parser cannot be configured securely", e);
        }
    }

    /**
     * Tracks the element being read, so an error can say where it is without quoting what is there, and
     * refuses a document of another message type at its root element.
     */
    private final class ElementPath extends XMLFilterImpl {

        private final Deque<String> elements = new ArrayDeque<>();

        ElementPath(XMLReader parser) {
            super(parser);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            if (elements.isEmpty() && !namespace.equals(uri)) {
                throw new InvalidMessageException("Expected a " + messageName + " document in namespace " + namespace
                        + ", got " + (uri.isEmpty() ? "no namespace" : uri));
            }
            elements.addLast(localName);
            super.startElement(uri, localName, qName, attributes);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            super.endElement(uri, localName, qName);
            elements.removeLast();
        }

        /** e.g. "/Document/FIToFICstmrCdtTrf/GrpHdr/MsgId" */
        String current() {
            return "/" + String.join("/", elements);
        }
    }

    /** Refuses the message at the first error. */
    private final class Refusal implements ErrorHandler {

        private final ElementPath path;

        Refusal(ElementPath path) {
            this.path = path;
        }

        @Override
        public void warning(SAXParseException exception) {
            // A warning never makes a message invalid.
        }

        /** A broken schema rule. The validator's text quotes the value, so neither it nor the exception goes along. */
        @Override
        public void error(SAXParseException exception) {
            Matcher rule = RULE.matcher(String.valueOf(exception.getMessage()));
            String ruleName = rule.find() ? rule.group() : "schema rule";
            throw new InvalidMessageException("The " + messageName + " message breaks its schema at " + path.current()
                    + " (" + ruleName + ", line " + exception.getLineNumber() + ")");
        }

        /** Not well-formed XML, or a DOCTYPE. The parser's text names the problem, not the content. */
        @Override
        public void fatalError(SAXParseException exception) {
            throw new InvalidMessageException(
                    "The " + messageName + " message is not well-formed XML: " + exception.getMessage(), exception);
        }
    }
}
