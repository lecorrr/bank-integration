package dev.tkachuk.interbank.iso20022;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

final class XmlDates {

    private static final DatatypeFactory DATATYPES = DatatypeFactory.newDefaultInstance();

    private XmlDates() {
    }

    static XMLGregorianCalendar toXml(Instant instant) {
        return DATATYPES.newXMLGregorianCalendar(instant.toString());
    }

    static XMLGregorianCalendar toXml(LocalDate date) {
        return DATATYPES.newXMLGregorianCalendarDate(date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                DatatypeConstants.FIELD_UNDEFINED);
    }

    static Instant toInstant(String field, XMLGregorianCalendar value) {
        try {
            return Instant.parse(value.toXMLFormat());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be a date-time with a zone or offset", e);
        }
    }

    static LocalDate toLocalDate(String field, XMLGregorianCalendar value) {
        try {
            return LocalDate.of(value.getYear(), value.getMonth(), value.getDay());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(field + " must be a date (yyyy-MM-dd)", e);
        }
    }
}
