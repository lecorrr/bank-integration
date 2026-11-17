package dev.tkachuk.interbank.iso20022;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * Converts between java.time and XMLGregorianCalendar, the type JAXB generates for ISODateTime (xs:dateTime)
 * and ISODate (xs:date).
 */
final class XmlDates {

    /** The JDK's own factory keeps no state, so one instance serves every thread. */
    private static final DatatypeFactory DATATYPES = DatatypeFactory.newDefaultInstance();

    private XmlDates() {
    }

    /** In UTC with every digit of the instant, e.g. 2026-11-17T08:30:00Z. */
    static XMLGregorianCalendar toXml(Instant instant) {
        return DATATYPES.newXMLGregorianCalendar(instant.toString());
    }

    static XMLGregorianCalendar toXml(LocalDate date) {
        return DATATYPES.newXMLGregorianCalendarDate(date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                DatatypeConstants.FIELD_UNDEFINED);
    }

    /** A date-time with a zone or offset, as both banks send it; a local time without one would be ambiguous. */
    static Instant toInstant(String field, XMLGregorianCalendar value) {
        try {
            return Instant.parse(value.toXMLFormat());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be a date-time with a zone or offset", e);
        }
    }

    /** The day; a zone, which xs:date allows, does not change which business day it is. */
    static LocalDate toLocalDate(String field, XMLGregorianCalendar value) {
        try {
            return LocalDate.of(value.getYear(), value.getMonth(), value.getDay());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(field + " must be a date (yyyy-MM-dd)", e);
        }
    }
}
