package dev.tkachuk.partnerbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StatementPropertiesTest {

    @Test
    void shouldUploadUnderTemporaryNameWithoutCheckingHostKey() {
        assertThat(TestStatements.SFTP.checksHostKey()).isFalse();
        assertThat(TestStatements.SFTP.uri()).isEqualTo("sftp://localhost:2222/statements?username=bnkc"
                + "&password=RAW(bnkc)&strictHostKeyChecking=no&useUserKnownHostsFile=false"
                + "&tempFileName=${file:name}.part&disconnect=true");
    }

    @Test
    void shouldCheckHostKeyAgainstKnownHostsFile() {
        StatementProperties.Sftp sftp =
                new StatementProperties.Sftp("sftp", 22, "bnkc", "bnkc", "statements", "/etc/partner/known_hosts");

        assertThat(sftp.checksHostKey()).isTrue();
        assertThat(sftp.uri()).contains("&strictHostKeyChecking=yes&knownHostsFile=/etc/partner/known_hosts");
    }

    @Test
    void shouldTreatBlankKnownHostsFileAsNone() {
        StatementProperties.Sftp sftp = new StatementProperties.Sftp("sftp", 22, "bnkc", "bnkc", "statements", " ");

        assertThat(sftp.checksHostKey()).isFalse();
    }

    static Stream<Arguments> wrongStatementSettings() {
        return Stream.of(
                Arguments.of("an IBAN with wrong check digits", "UA863888880000000000000000399",
                        new BigDecimal("1000.00"), TestStatements.SFTP, "partner-bank.statements.account-iban"),
                Arguments.of("a fraction of a kopiyka", TestStatements.ACCOUNT, new BigDecimal("1000.001"),
                        TestStatements.SFTP, "partner-bank.statements.opening-balance"),
                Arguments.of("no opening balance", TestStatements.ACCOUNT, null, TestStatements.SFTP,
                        "partner-bank.statements.opening-balance"),
                Arguments.of("no SFTP server", TestStatements.ACCOUNT, new BigDecimal("1000.00"), null,
                        "partner-bank.statements.sftp"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongStatementSettings")
    void shouldRejectInvalidStatementSettingAndNameIt(String setting, String accountIban, BigDecimal openingBalance,
            StatementProperties.Sftp sftp, String named) {
        assertThatThrownBy(() -> new StatementProperties(accountIban, openingBalance, sftp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(named);
    }

    static Stream<Arguments> wrongSftpSettings() {
        return Stream.of(
                Arguments.of("no host", " ", 22, "bnkc", "bnkc", "statements", "sftp.host"),
                Arguments.of("port 0", "sftp", 0, "bnkc", "bnkc", "statements", "sftp.port"),
                Arguments.of("a port above 65535", "sftp", 65_536, "bnkc", "bnkc", "statements", "sftp.port"),
                Arguments.of("no user", "sftp", 22, null, "bnkc", "statements", "sftp.username"),
                Arguments.of("no password", "sftp", 22, "bnkc", null, "statements", "sftp.password"),
                Arguments.of("no folder", "sftp", 22, "bnkc", "bnkc", "", "sftp.directory"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongSftpSettings")
    void shouldRejectInvalidSftpSettingAndNameIt(String setting, String host, int port, String username,
            String password, String directory, String named) {
        assertThatThrownBy(() -> new StatementProperties.Sftp(host, port, username, password, directory, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partner-bank.statements." + named);
    }
}
