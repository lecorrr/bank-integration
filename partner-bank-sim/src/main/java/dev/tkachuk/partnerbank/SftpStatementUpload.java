package dev.tkachuk.partnerbank;

import java.nio.charset.StandardCharsets;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Uploads statements to the SFTP folder from partner-bank.statements.sftp. */
@Component
class SftpStatementUpload implements StatementUpload {

    private static final Logger log = LoggerFactory.getLogger(SftpStatementUpload.class);

    private final ProducerTemplate producer;
    private final String uri;

    SftpStatementUpload(ProducerTemplate producer, StatementProperties properties) {
        this.producer = producer;
        this.uri = properties.sftp().uri();
        if (!properties.sftp().checksHostKey()) {
            log.warn("The SFTP server's host key is not checked; "
                    + "set partner-bank.statements.sftp.known-hosts-file to check it");
        }
    }

    @Override
    public void upload(String fileName, String xml) {
        producer.sendBodyAndHeader(uri, xml.getBytes(StandardCharsets.UTF_8), Exchange.FILE_NAME, fileName);
    }
}
