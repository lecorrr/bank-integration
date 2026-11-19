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

/** Reads and writes one ISO 20022 message type with JAXB and validates it against the official XSD both ways. */
final class Iso20022Xml<D> {

    private static final Pattern RULE = Pattern.compile("cvc-[\\w.-]+(?=:)");

    private final String messageName;
    private final String namespace;
    private final Class<D> documentType;
    private final Function<D, JAXBElement<D>> asRootElement;
    private final JAXBContext context;
    private final Schema schema;

    Iso20022Xml(String messageName, String namespace, Class<D> documentType,
            Function<D, JAXBElement<D>> asRootElement) {
        this.messageName = messageName;
        this.namespace = namespace;
        this.documentType = documentType;
        this.asRootElement = asRootElement;
        this.context = newContext(documentType);
        this.schema = loadSchema("/xsd/" + messageName + ".xsd");
    }

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

    D read(String xml) {
        check(xml);
        try {
            SAXSource source = new SAXSource(newSecureParser(), new InputSource(new StringReader(xml)));
            return context.createUnmarshaller().unmarshal(source, documentType).getValue();
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot bind a " + messageName + " message that its schema accepts", e);
        }
    }

    <T> T required(T value, String path) {
        if (value == null) {
            throw new InvalidMessageException("The " + messageName + " message has no " + path);
        }
        return value;
    }

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

    private static Schema loadSchema(String resource) {
        URL url = Iso20022Xml.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("The schema " + resource + " is not on the classpath");
        }
        try (InputStream xsd = url.openStream()) {
            SchemaFactory factory = SchemaFactory.newDefaultInstance();
            // Only the bundled XSD, never one a document points to.
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
            // No DOCTYPE at all: rules out XXE and entity expansion.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            return factory.newSAXParser().getXMLReader();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("This JDK's XML parser cannot be configured securely", e);
        }
    }

    /** Tracks where the parser is, for error messages. */
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

        String current() {
            return "/" + String.join("/", elements);
        }
    }

    private final class Refusal implements ErrorHandler {

        private final ElementPath path;

        Refusal(ElementPath path) {
            this.path = path;
        }

        @Override
        public void warning(SAXParseException exception) {
        }

        @Override
        public void error(SAXParseException exception) {
            // The validator's message quotes the value (often a name or IBAN), so only the path and rule are reported.
            Matcher rule = RULE.matcher(String.valueOf(exception.getMessage()));
            String ruleName = rule.find() ? rule.group() : "schema rule";
            throw new InvalidMessageException("The " + messageName + " message breaks its schema at " + path.current()
                    + " (" + ruleName + ", line " + exception.getLineNumber() + ")");
        }

        @Override
        public void fatalError(SAXParseException exception) {
            throw new InvalidMessageException(
                    "The " + messageName + " message is not well-formed XML: " + exception.getMessage(), exception);
        }
    }
}
