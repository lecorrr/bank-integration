package dev.tkachuk.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

/** The partner bank's side of the statements folder: drops files, and shows where integration-service put them. */
public class TestSftp {

    private static final String FOLDER = "/home/" + SftpTestcontainer.USER + "/statements";

    private final GenericContainer<?> sftp;
    private final ProducerTemplate producer;
    private final String uri;

    TestSftp(GenericContainer<?> sftp, ProducerTemplate producer) {
        this.sftp = sftp;
        this.producer = producer;
        this.uri = "sftp://" + sftp.getHost() + ":" + sftp.getMappedPort(SftpTestcontainer.SSH_PORT) + "/statements"
                + "?username=" + SftpTestcontainer.USER
                + "&password=RAW(" + SftpTestcontainer.PASSWORD + ")"
                + "&strictHostKeyChecking=no&useUserKnownHostsFile=false"
                + "&tempFileName=${file:name}.part&disconnect=true";
    }

    /** Uploads the way the partner bank does: under a temporary name, renamed once complete. */
    public void drop(String fileName, String content) {
        producer.sendBodyAndHeader(uri, content.getBytes(StandardCharsets.UTF_8), Exchange.FILE_NAME, fileName);
    }

    /** Files in the statements folder ("" for the folder itself), without subfolders; none if it doesn't exist. */
    public List<String> files(String subfolder) throws IOException, InterruptedException {
        Container.ExecResult result = sftp.execInContainer("ls", "-1p", FOLDER + "/" + subfolder);
        if (result.getExitCode() != 0) {
            return List.of();
        }
        return result.getStdout().lines().filter(name -> !name.endsWith("/")).toList();
    }
}
