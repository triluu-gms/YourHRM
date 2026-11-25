package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A Time &amp; Attendance domain event ({@code att.<Aggregate><PastTenseVerb>}, event catalogue v1.1). Written to
 * {@code att.outbox_event} in the transaction of the change and published in-process. The envelope fields map to the
 * outbox columns; the record as a whole is the JSON payload. Payloads never carry P3 values (D7).
 */
public interface AttendanceEvent {

    /** e.g. {@code att.HolidayCalendarChanged}. */
    String eventType();

    UUID tenantId();

    /** The employer the event is about; null when the event spans several legal entities. */
    UUID legalEntityId();

    /** Bus partition key type, e.g. {@code holiday_calendar}. */
    String aggregateType();

    UUID aggregateId();

    /** Earliest business date the change applies to. */
    LocalDate effectiveDate();

    /** The change touches a CLOSED or LOCKED attendance period (BR-ATT-006, BR-ATT-071). */
    default boolean retro() {
        return false;
    }

    default int schemaVersion() {
        return 1;
    }
}
