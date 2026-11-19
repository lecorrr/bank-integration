package dev.tkachuk.integration.reconciliation;

import dev.tkachuk.integration.InterbankProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** reconciliation.*: our account with the partner bank, and the SFTP folder its statements arrive in. */
@ConfigurationProperties("reconciliation")
record ReconciliationProperties(String accountIban, Duration pollInterval, Sftp sftp) {

    ReconciliationProperties {
        if (InterbankProperties.bankCodeOf(accountIban).isEmpty()) {
            throw new IllegalArgumentException(
                    "reconciliation.account-iban must be a Ukrainian IBAN in capitals without spaces");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("reconciliation.poll-interval must be positive");
        }
        if (sftp == null) {
            throw new IllegalArgumentException("reconciliation.sftp is not set");
        }
    }

    /** The partner bank's SFTP server. Without a known_hosts file its host key is not checked. */
    record Sftp(String host, int port, String username, String password, String directory, String knownHostsFile) {

        Sftp {
            requireText("reconciliation.sftp.host", host);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("reconciliation.sftp.port must be 1 to 65535, got " + port);
            }
            requireText("reconciliation.sftp.username", username);
            if (password == null) {
                throw new IllegalArgumentException("reconciliation.sftp.password is not set");
            }
            requireText("reconciliation.sftp.directory", directory);
            if (knownHostsFile != null && knownHostsFile.isBlank()) {
                knownHostsFile = null;
            }
        }

        boolean checksHostKey() {
            return knownHostsFile != null;
        }

        /** Camel's sftp endpoint for the folder, without consumer options. RAW keeps the password as it is. */
        String uri() {
            String hostKey = checksHostKey()
                    ? "&strictHostKeyChecking=yes&knownHostsFile=" + knownHostsFile
                    : "&strictHostKeyChecking=no";
            return "sftp://" + host + ":" + port + "/" + directory
                    + "?username=" + username
                    + "&password=RAW(" + password + ")"
                    + hostKey
                    // Never read or write ~/.ssh/known_hosts of whoever runs the service.
                    + "&useUserKnownHostsFile=false";
        }

        private static void requireText(String property, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(property + " is not set");
            }
        }
    }
}
