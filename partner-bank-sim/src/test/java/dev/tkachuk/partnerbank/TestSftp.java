package dev.tkachuk.partnerbank;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

/** Reads what the simulator left on the SFTP server, as bank-core's bank would. */
final class TestSftp {

    private static final String FOLDER = "/home/" + SftpTestcontainer.USER + "/statements";

    private final GenericContainer<?> sftp;

    TestSftp(GenericContainer<?> sftp) {
        this.sftp = sftp;
    }

    String read(String fileName) throws Exception {
        Container.ExecResult result = sftp.execInContainer("cat", FOLDER + "/" + fileName);
        if (result.getExitCode() != 0) {
            throw new AssertionError("No file " + fileName + " in " + FOLDER + ": " + result.getStderr());
        }
        return result.getStdout();
    }
}
