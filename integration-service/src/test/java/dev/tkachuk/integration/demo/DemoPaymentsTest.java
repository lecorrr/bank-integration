package dev.tkachuk.integration.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.interbank.iso20022.Pacs008;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DemoPaymentsTest {

    @Test
    void settlesOnTheKyivBusinessDay() {
        // 23:30 UTC on the 16th is already 01:30 on the 17th in Kyiv (UTC+2 in November).
        Clock lateEvening = Clock.fixed(Instant.parse("2026-11-16T23:30:00Z"), ZoneOffset.UTC);

        Pacs008 payment = new DemoPayments(lateEvening).next();

        assertThat(payment.settlementDate()).isEqualTo(LocalDate.parse("2026-11-17"));
    }

    @Test
    void everyPaymentHasFreshIds() {
        DemoPayments demoPayments = new DemoPayments(Clock.systemUTC());

        Pacs008 first = demoPayments.next();
        Pacs008 second = demoPayments.next();

        assertThat(second.messageId()).isNotEqualTo(first.messageId());
        assertThat(second.endToEndId()).isNotEqualTo(first.endToEndId());
        assertThat(second.uetr()).isNotEqualTo(first.uetr());
    }

    @Test
    void isAValidPacs008() {
        Pacs008 payment = new DemoPayments(Clock.systemUTC()).next();

        assertThat(Pacs008.parse(payment.toXml())).isEqualTo(payment);
    }
}
