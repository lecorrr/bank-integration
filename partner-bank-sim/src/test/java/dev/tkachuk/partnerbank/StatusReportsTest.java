package dev.tkachuk.partnerbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.interbank.iso20022.InvalidMessageException;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusReportsTest {

    private static final Instant NOW = Instant.parse("2026-11-17T08:31:15Z");

    private final StatusReports statusReports = new StatusReports(Clock.fixed(NOW, ZoneOffset.UTC),
            new PartnerBankProperties(List.of(TestPayments.CLOSED_ACCOUNT)));

    @Test
    void acceptsAValidPaymentAndRepeatsItsIds() {
        Pacs008 payment = TestPayments.pacs008("MSG-1");

        Pacs002 report = statusReports.answer(payment.toXml());

        assertThat(report.status()).isEqualTo(TransactionStatus.ACSC);
        assertThat(report.reasonCode()).isNull();
        assertThat(report.originalMessageId()).isEqualTo("MSG-1");
        assertThat(report.originalUetr()).isEqualTo(payment.uetr());
        assertThat(report.createdAt()).isEqualTo(NOW);
    }

    @Test
    void refusesAPaymentToAClosedAccountSayingWhy() {
        Pacs008 payment = TestPayments.pacs008("MSG-1", TestPayments.CLOSED_ACCOUNT);

        Pacs002 report = statusReports.answer(payment.toXml());

        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isEqualTo("AC04");
        assertThat(report.originalUetr()).isEqualTo(payment.uetr());
        assertThat(report.createdAt()).isEqualTo(NOW);
    }

    @Test
    void givesEveryAnswerItsOwnMessageId() {
        Pacs002 first = statusReports.answer(TestPayments.pacs008("MSG-1").toXml());
        Pacs002 second = statusReports.answer(TestPayments.pacs008("MSG-2").toXml());

        assertThat(second.messageId()).isNotEqualTo(first.messageId());
    }

    @Test
    void refusesAnythingThatIsNotAPacs008() {
        assertThatThrownBy(() -> statusReports.answer("<Document/>"))
                .isInstanceOf(InvalidMessageException.class);
    }
}
