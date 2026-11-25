package vn.yourhrm.attendance.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code att.ScheduleAssignmentChanged} (BR-ATT-015/038): the schedule of one assignment line changed (rule saved,
 * manual override, People assignment or employment change). Daily processing recalculates the days of
 * {@code [rangeFrom, rangeTo]} that lie in OPEN / IN_REVIEW periods; days of CLOSED / LOCKED periods
 * ({@code affectedPeriodIds}, {@link #retro()}) are never changed in place (BR-ATT-071).
 *
 * <p>Not in event catalogue v1.1 (T&amp;A proposal, to be added). No PII in the payload.
 *
 * @param cause     RULE, MANUAL, PEOPLE_ASSIGNMENT or EMPLOYMENT
 * @param rangeFrom first day whose schedule changed (inclusive)
 * @param rangeTo   last day whose schedule changed (inclusive); null = open-ended
 */
public record ScheduleAssignmentChanged(
        UUID tenantId,
        UUID legalEntityId,
        UUID employmentId,
        UUID assignmentKey,
        String cause,
        LocalDate rangeFrom,
        LocalDate rangeTo,
        boolean scheduled,
        List<UUID> affectedPeriodIds,
        boolean retro) implements AttendanceEvent {

    public static final String TYPE = "att.ScheduleAssignmentChanged";

    public ScheduleAssignmentChanged {
        affectedPeriodIds = List.copyOf(affectedPeriodIds);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "employment";
    }

    @Override
    public UUID aggregateId() {
        return employmentId;
    }

    @Override
    public LocalDate effectiveDate() {
        return rangeFrom;
    }
}
