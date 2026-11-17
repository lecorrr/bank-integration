package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class XmlTemplateTest {

    private final XmlTemplate template = new XmlTemplate("test", "<A b=\"${first}\">${second}</A>");

    @Test
    void fillsEveryPlaceholder() {
        assertThat(template.render(Map.of("first", "1", "second", "2"))).isEqualTo("<A b=\"1\">2</A>");
    }

    @Test
    void refusesAPlaceholderWithoutAValue() {
        assertThatThrownBy(() -> template.render(Map.of("first", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${second}");
    }

    @Test
    void refusesAValueWithoutAPlaceholder() {
        assertThatThrownBy(() -> template.render(Map.of("first", "1", "second", "2", "third", "3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("third");
    }

    @Test
    void refusesAMissingResource() {
        assertThatThrownBy(() -> XmlTemplate.load("no-such-message.xml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-message.xml");
    }
}
