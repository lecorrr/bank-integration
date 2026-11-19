package dev.tkachuk.integration.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReconciliationPropertiesTest {

    static final String ACCOUNT = "UA853888880000000000000000399";
    static final ReconciliationProperties.Sftp SFTP =
            new ReconciliationProperties.Sftp("sftp", 22, "bnkc", "s3cr&t+1", "statements", null);

    @Test
    void shouldReadNewXmlFilesAndMoveThemToTheirFolder() {
        ReconciliationProperties properties = new ReconciliationProperties(ACCOUNT, Duration.ofSeconds(10), SFTP);

        assertThat(StatementRoute.endpointUri(properties)).isEqualTo("sftp://sftp:22/statements?username=bnkc"
                + "&password=RAW(s3cr&t+1)&strictHostKeyChecking=no&useUserKnownHostsFile=false&antInclude=*.xml"
                + "&sortBy=file:name&charset=UTF-8&delay=10000&move=${exchangeProperty.statementFolder}/${file:name}");
    }

    @Test
    void shouldCheckHostKeyAgainstKnownHostsFile() {
        ReconciliationProperties.Sftp sftp =
                new ReconciliationProperties.Sftp("sftp", 22, "bnkc", "bnkc", "statements", "/etc/bank/known_hosts");

        assertThat(sftp.checksHostKey()).isTrue();
        assertThat(sftp.uri()).contains("&strictHostKeyChecking=yes&knownHostsFile=/etc/bank/known_hosts")
                .contains("&useUserKnownHostsFile=false");
    }

    @Test
    void shouldTreatBlankKnownHostsFileAsNone() {
        ReconciliationProperties.Sftp sftp = new ReconciliationProperties.Sftp("sftp", 22, "bnkc", "", "statements",
                "  ");

        assertThat(sftp.checksHostKey()).isFalse();
        assertThat(sftp.uri()).contains("&password=RAW()&strictHostKeyChecking=no");
    }

    static Stream<Arguments> wrongSettings() {
        return Stream.of(
                Arguments.of("an IBAN with a space", "UA85 3888880000000000000000399", Duration.ofMinutes(1), SFTP,
                        "reconciliation.account-iban"),
                Arguments.of("no poll interval", ACCOUNT, null, SFTP, "reconciliation.poll-interval"),
                Arguments.of("a zero poll interval", ACCOUNT, Duration.ZERO, SFTP, "reconciliation.poll-interval"),
                Arguments.of("no SFTP server", ACCOUNT, Duration.ofMinutes(1), null, "reconciliation.sftp"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongSettings")
    void shouldRejectInvalidSettingAndNameIt(String setting, String accountIban, Duration pollInterval,
            ReconciliationProperties.Sftp sftp, String named) {
        assertThatThrownBy(() -> new ReconciliationProperties(accountIban, pollInterval, sftp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(named);
    }

    static Stream<Arguments> wrongSftpSettings() {
        return Stream.of(
                Arguments.of("no host", null, 22, "bnkc", "bnkc", "statements", "host"),
                Arguments.of("port 0", "sftp", 0, "bnkc", "bnkc", "statements", "port"),
                Arguments.of("a port above 65535", "sftp", 65_536, "bnkc", "bnkc", "statements", "port"),
                Arguments.of("no user", "sftp", 22, " ", "bnkc", "statements", "username"),
                Arguments.of("no password", "sftp", 22, "bnkc", null, "statements", "password"),
                Arguments.of("no folder", "sftp", 22, "bnkc", "bnkc", null, "directory"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongSftpSettings")
    void shouldRejectInvalidSftpSettingAndNameIt(String setting, String host, int port, String username,
            String password, String directory, String named) {
        assertThatThrownBy(() -> new ReconciliationProperties.Sftp(host, port, username, password, directory, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconciliation.sftp." + named);
    }
}
