package dev.tkachuk.interbank.iso20022;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Valid messages for the tests. */
final class TestMessages {

    static final Instant CREATED_AT = Instant.parse("2026-11-17T08:30:00Z");

    private TestMessages() {
    }

    static Pacs008 pacs008() {
        return pacs008("Olena Kovalenko");
    }

    static Camt053 statement() {
        return statement(new BigDecimal("1000000.00"), List.of(
                new Camt053.Entry("PRTB-0001", CreditDebit.DBIT, new BigDecimal("150.00"),
                        "3b241101-e2bb-4255-8caf-4136c566a962", "E2E-20261117-0001"),
                new Camt053.Entry("PRTB-0002", CreditDebit.CRDT, new BigDecimal("250.00"),
                        "7d4c2f1e-8a9b-4c3d-9e5f-1a2b3c4d5e6f", "NOTPROVIDED")));
    }

    static Camt053 statement(BigDecimal openingBalance, List<Camt053.Entry> entries) {
        BigDecimal closingBalance = openingBalance;
        for (Camt053.Entry entry : entries) {
            closingBalance = closingBalance.add(entry.signedAmount());
        }
        return new Camt053("STATEMENT-20261117", Instant.parse("2026-11-17T21:55:00Z"), "STMT-20261117",
                "UA853888880000000000000000399", "PRTBUAUKXXX", "UAH", LocalDate.parse("2026-11-17"), openingBalance,
                closingBalance, entries);
    }

    static Pacs028 pacs028() {
        return Pacs028.about(pacs008(), "STATUS-REQUEST-1", CREATED_AT.plusSeconds(60));
    }

    static Pacs008 pacs008(String creditorName) {
        return new Pacs008(
                "5f0c1e3a9b2d4e6f8a1b3c5d7e9f0a1b",
                CREATED_AT,
                "E2E-20261117-0001",
                "TX-20261117-0001",
                "3b241101-e2bb-4255-8caf-4136c566a962",
                new BigDecimal("150.00"),
                "UAH",
                LocalDate.parse("2026-11-17"),
                "Artem Tkachuk",
                "UA843999990000000000000000001",
                "BNKCUAUKXXX",
                creditorName,
                "UA643888880000000000000000777",
                "PRTBUAUKXXX",
                "Rent for November");
    }
}
