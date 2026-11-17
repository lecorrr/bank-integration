package dev.tkachuk.interbank.iso20022;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An XML message with ${name} placeholders, kept as a resource next to this class. Values are escaped for
 * XML and filled in one pass, so a value can never add elements or be read as another placeholder.
 * (Step 2 replaces the templates with classes generated from the official XSDs.)
 */
record XmlTemplate(String name, String text) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z]+)}");

    static XmlTemplate load(String resource) {
        try (InputStream in = XmlTemplate.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing template resource " + resource + " next to XmlTemplate");
            }
            return new XmlTemplate(resource, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read template resource " + resource, e);
        }
    }

    /**
     * @throws IllegalArgumentException when a placeholder has no value or a value has no placeholder,
     *                                  so a renamed field cannot silently drop out of the message
     */
    String render(Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder xml = new StringBuilder(text.length() + 256);
        Set<String> used = new HashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException(name + " needs a value for ${" + key + "}");
            }
            used.add(key);
            matcher.appendReplacement(xml, Matcher.quoteReplacement(escape(value)));
        }
        matcher.appendTail(xml);
        Set<String> unused = new TreeSet<>(values.keySet());
        unused.removeAll(used);
        if (!unused.isEmpty()) {
            throw new IllegalArgumentException(name + " has no placeholder for " + unused);
        }
        return xml.toString();
    }

    /** Safe inside both element text and double- or single-quoted attributes. */
    static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
