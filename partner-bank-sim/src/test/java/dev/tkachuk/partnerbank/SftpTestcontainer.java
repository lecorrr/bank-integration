package dev.tkachuk.partnerbank;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/** The SFTP server bank-core's bank collects its statements from, as in compose.yaml. */
@TestConfiguration(proxyBeanMethods = false)
public class SftpTestcontainer {

    static final String USER = "bnkc";
    static final String PASSWORD = "bnkc";
    static final int SSH_PORT = 22;

    @Bean
    GenericContainer<?> sftp() {
        String image = System.getProperty("sftp.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("System property sftp.image is not set. Run the tests through Maven "
                    + "(./mvnw verify), or pass -Dsftp.image with the tag from pom.xml.");
        }
        return new GenericContainer<>(image)
                .withCommand(USER + ":" + PASSWORD + ":1001:1001:statements")
                .withExposedPorts(SSH_PORT);
    }

    @Bean
    DynamicPropertyRegistrar sftpProperties(GenericContainer<?> sftp) {
        return registry -> {
            registry.add("partner-bank.statements.sftp.host", sftp::getHost);
            registry.add("partner-bank.statements.sftp.port", () -> sftp.getMappedPort(SSH_PORT));
            registry.add("partner-bank.statements.sftp.username", () -> USER);
            registry.add("partner-bank.statements.sftp.password", () -> PASSWORD);
        };
    }

    @Bean
    TestSftp testSftp(GenericContainer<?> sftp) {
        return new TestSftp(sftp);
    }
}
