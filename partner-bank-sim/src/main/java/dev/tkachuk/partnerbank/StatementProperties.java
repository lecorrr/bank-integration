package dev.tkachuk.partnerbank;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** partner-bank.statements.*: bank-core's bank's account here, and the SFTP folder its statements go to. */
@ConfigurationProperties("partner-bank.statements")
record StatementProperties(String accountIban, BigDecimal openingBalance, Sftp sftp) {

    private static final int KOPIYKY_DIGITS = 2;

    StatementProperties {
        if (!Ibans.isValid(accountIban)) {
            throw new IllegalArgumentException("partner-bank.statements.account-iban must be an IBAN in capitals "
                    + "without spaces, with valid check digits");
        }
        if (openingBalance == null || openingBalance.stripTrailingZeros().scale() > KOPIYKY_DIGITS) {
            throw new IllegalArgumentException(
                    "partner-bank.statements.opening-balance must be an amount with at most two decimals");
        }
        if (sftp == null) {
            throw new IllegalArgumentException("partner-bank.statements.sftp is not set");
        }
    }

    /** Where bank-core's bank collects its statements. Without a known_hosts file the host key is not checked. */
    record Sftp(String host, int port, String username, String password, String directory, String knownHostsFile) {

        Sftp {
            requireText("partner-bank.statements.sftp.host", host);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "partner-bank.statements.sftp.port must be 1 to 65535, got " + port);
            }
            requireText("partner-bank.statements.sftp.username", username);
            if (password == null) {
                throw new IllegalArgumentException("partner-bank.statements.sftp.password is not set");
            }
            requireText("partner-bank.statements.sftp.directory", directory);
            if (knownHostsFile != null && knownHostsFile.isBlank()) {
                knownHostsFile = null;
            }
        }

        boolean checksHostKey() {
            return knownHostsFile != null;
        }

        /**
         * Camel's sftp endpoint for the folder. A file is written under a .part name and renamed when complete, so
         * the reader never sees half a statement.
         */
        String uri() {
            String hostKey = checksHostKey()
                    ? "&strictHostKeyChecking=yes&knownHostsFile=" + knownHostsFile
                    : "&strictHostKeyChecking=no";
            return "sftp://" + host + ":" + port + "/" + directory
                    + "?username=" + username
                    + "&password=RAW(" + password + ")"
                    + hostKey
                    + "&useUserKnownHostsFile=false"
                    + "&tempFileName=${file:name}.part"
                    + "&disconnect=true";
        }

        private static void requireText(String property, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(property + " is not set");
            }
        }
    }
}
