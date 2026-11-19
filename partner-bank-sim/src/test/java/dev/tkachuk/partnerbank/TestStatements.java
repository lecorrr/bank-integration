package dev.tkachuk.partnerbank;

import java.math.BigDecimal;

/** Statement settings as in application.yml. */
final class TestStatements {

    static final String ACCOUNT = "UA853888880000000000000000399";
    static final StatementProperties.Sftp SFTP =
            new StatementProperties.Sftp("localhost", 2222, "bnkc", "bnkc", "statements", null);
    static final StatementProperties PROPERTIES = new StatementProperties(ACCOUNT, new BigDecimal("1000.00"), SFTP);
    static final SendingProperties SENDING = new SendingProperties("PRTBUAUKXXX", TestPayments.OPEN_ACCOUNT,
            "Olena Kovalenko", "BNKCUAUKXXX");

    private TestStatements() {
    }
}
