package dev.tkachuk.integration;

import org.apache.camel.ProducerTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/** The partner bank's SFTP server with our bank's statements folder, as in compose.yaml. */
@TestConfiguration(proxyBeanMethods = false)
public class SftpTestcontainer {

    static final String USER = "bnkc";
    static final String PASSWORD = "bnkc";
    static final int SSH_PORT = 22;

    @Bean
    GenericContainer<?> sftp() {
        return new GenericContainer<>(TestImages.fromSystemProperty("sftp.image"))
                .withCommand(USER + ":" + PASSWORD + ":1001:1001:statements")
                .withExposedPorts(SSH_PORT);
    }

    @Bean
    DynamicPropertyRegistrar sftpProperties(GenericContainer<?> sftp) {
        return registry -> {
            registry.add("reconciliation.sftp.host", sftp::getHost);
            registry.add("reconciliation.sftp.port", () -> sftp.getMappedPort(SSH_PORT));
            registry.add("reconciliation.sftp.username", () -> USER);
            registry.add("reconciliation.sftp.password", () -> PASSWORD);
        };
    }

    @Bean
    TestSftp testSftp(GenericContainer<?> sftp, ProducerTemplate producer) {
        return new TestSftp(sftp, producer);
    }
}
