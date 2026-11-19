package dev.tkachuk.interbank.iso20022;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XmlDatesTest {

    @Test
    void shouldKeepEveryDigitOfInstant() {
        Instant instant = Instant.parse("2026-11-17T08:30:00.123456789Z");

        assertThat(XmlDates.toXml(instant).toXMLFormat()).isEqualTo("2026-11-17T08:30:00.123456789Z");
        assertThat(XmlDates.toInstant("CreDtTm", XmlDates.toXml(instant))).isEqualTo(instant);
    }

    @Test
    void shouldReadOffsetDateTimeAsSameInstant() {
        assertThat(XmlDates.toInstant("CreDtTm", xml("2026-11-17T10:30:00+02:00")))
                .isEqualTo(Instant.parse("2026-11-17T08:30:00Z"));
    }

    @Test
    void shouldRejectDateTimeWithoutZone() {
        assertThatThrownBy(() -> XmlDates.toInstant("CreDtTm", xml("2026-11-17T08:30:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CreDtTm must be a date-time with a zone or offset");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-17", "2026-11-17Z", "2026-11-17+02:00"})
    void shouldReadDateWithOrWithoutZone(String date) {
        assertThat(XmlDates.toLocalDate("IntrBkSttlmDt", xml(date))).isEqualTo(LocalDate.parse("2026-11-17"));
    }

    private static XMLGregorianCalendar xml(String lexical) {
        return DatatypeFactory.newDefaultInstance().newXMLGregorianCalendar(lexical);
    }
}
