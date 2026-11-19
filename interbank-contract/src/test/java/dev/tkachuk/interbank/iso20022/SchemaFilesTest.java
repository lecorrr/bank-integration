package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchemaFilesTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "pacs.008.001.08, cf048155f70cacd03f1f1dfadf66cba8752be93c249c267351782fd6adb023f7",
            "pacs.002.001.10, d14da6304db5178b1afc7d8ed6cc6073e6e1a143fc79d9ba66d3246d4a654e90",
            "pacs.028.001.03, b683ae636887af4ff9d71e05b95e9a27aa54b96b4cb6d1858adc2e8d23b17499",
            "camt.053.001.08, 338e9cb0c9989b5181802a7b773eece070d6815fc9d6483ac0579117bc24ccba"})
    void shouldMatchOfficialFile(String messageName, String sha256) throws Exception {
        try (InputStream xsd = SchemaFilesTest.class.getResourceAsStream("/xsd/" + messageName + ".xsd")) {
            assertThat(xsd).isNotNull();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(xsd.readAllBytes());

            assertThat(HexFormat.of().formatHex(digest)).isEqualTo(sha256);
        }
    }
}
