package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code att.HolidayCalendarChanged} (ATT-P-16, BR-ATT-006): a holiday calendar, one of its dates or one of its
 * legal-entity assignments changed. Consumers (Core SLA calendars, Payroll standard days, Recruitment, T&amp;A daily
 * processing) re-read the resolved calendar through {@link HolidayCalendars} for {@code [rangeFrom, rangeTo]} of
 * {@code legalEntityIds}.
 *
 * <p>Days of OPEN / IN_REVIEW periods in the range are recalculated in place; days of CLOSED or LOCKED periods
 * ({@code affectedPeriodIds}, {@link #retro()}) are never changed in place: they become retro deltas of the next open
 * period (BR-ATT-071) or wait for a reopen.
 *
 * @param change            what changed, see {@link Change}
 * @param changedDates      the holiday dates added, changed or cancelled (empty for assignment / status changes)
 * @param legalEntityIds    legal entities whose resolved calendar is affected in the range
 * @param rangeFrom         first affected day (inclusive)
 * @param rangeTo           last affected day (inclusive); null = open-ended (assignment without end)
 * @param affectedPeriodIds CLOSED / LOCKED attendance periods of those legal entities overlapping the changed days
 */
public record HolidayCalendarChanged(
        UUID tenantId,
        UUID calendarId,
        String calendarCode,
        Change change,
        List<LocalDate> changedDates,
        List<UUID> legalEntityIds,
        LocalDate rangeFrom,
        LocalDate rangeTo,
        List<UUID> affectedPeriodIds,
        boolean retro) implements AttendanceEvent {

    public static final String TYPE = "att.HolidayCalendarChanged";

    public enum Change {
        /** A date was added to the calendar. */
        DATE_ADDED,
        /** A date was changed (name, paid flag, lunar reference, ...). */
        DATE_CHANGED,
        /** A date was cancelled (tenant override of the template, or a correction). */
        DATE_CANCELLED,
        /** Differences of the platform VN template for a year were accepted (BR-ATT-002). */
        TEMPLATE_APPLIED,
        /** A legal-entity / location assignment was added or its validity changed. */
        ASSIGNMENT_CHANGED
    }

    public HolidayCalendarChanged {
        changedDates = List.copyOf(changedDates);
        legalEntityIds = List.copyOf(legalEntityIds);
        affectedPeriodIds = List.copyOf(affectedPeriodIds);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    /** The single affected legal entity, else null (the event spans several). */
    @Override
    public UUID legalEntityId() {
        return legalEntityIds.size() == 1 ? legalEntityIds.getFirst() : null;
    }

    @Override
    public String aggregateType() {
        return "holiday_calendar";
    }

    @Override
    public UUID aggregateId() {
        return calendarId;
    }

    @Override
    public LocalDate effectiveDate() {
        return rangeFrom;
    }
}
