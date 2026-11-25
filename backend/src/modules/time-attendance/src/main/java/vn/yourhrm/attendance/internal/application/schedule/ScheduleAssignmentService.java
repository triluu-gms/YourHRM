package vn.yourhrm.attendance.internal.application.schedule;

import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.invalid;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.WorkSchedules.PlannedDay;
import vn.yourhrm.attendance.internal.domain.schedule.DateRange;
import vn.yourhrm.attendance.internal.domain.schedule.StandardDays;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.AssignmentRow;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore.PeriodRow;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Schedule assignment per employee (BR-ATT-015/016, US-ATT-010/011/012): the schedules of an employment, manual
 * overrides of the rules, the planned days and the standard days for a period. Data-scoped per employment through the
 * People assignment projection (legal entity, org unit, location as of today, else the latest version).
 *
 * <p>A manual row overrides the rules for its period: overlapping rule rows are cancelled and the rules regenerate
 * around it. Rule rows cannot be cancelled by hand (change the rule, or add a manual row).
 */
@Service
public class ScheduleAssignmentService {

    public record ScheduleAssignmentView(UUID id, UUID employmentId, UUID assignmentKey, UUID patternId,
            String patternCode, UUID ruleId, LocalDate validFrom, LocalDate validTo, String source, String status,
            int version) {
    }

    /** A line of the "no schedule" list (US-ATT-011). */
    public record Unscheduled(UUID employmentId, UUID assignmentKey, String assignmentType, UUID orgUnitId,
            UUID locationId, LocalDate lineValidFrom) {
    }

    private final ScheduleAssignmentStore store;
    private final ShiftPatternStore patterns;
    private final ScheduleGenerator generator;
    private final WorkScheduleResolver resolver;
    private final AuthorizationService authz;
    private final BusinessClock clock;
    private final AttendancePeriodStore periods;

    public ScheduleAssignmentService(ScheduleAssignmentStore store, ShiftPatternStore patterns,
            ScheduleGenerator generator, WorkScheduleResolver resolver, AuthorizationService authz,
            BusinessClock clock, AttendancePeriodStore periods) {
        this.periods = periods;
        this.store = store;
        this.patterns = patterns;
        this.generator = generator;
        this.resolver = resolver;
        this.authz = authz;
        this.clock = clock;
    }

    @AttTransactional(readOnly = true)
    public List<ScheduleAssignmentView> list(UUID employmentId, boolean includeCancelled) {
        requireEmployment(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, employmentId);
        List<AssignmentRow> rows = store.assignmentsOfEmployment(employmentId, includeCancelled);
        var pats = patterns.patterns(rows.stream().map(AssignmentRow::patternId).distinct().toList());
        return rows.stream().map(a -> new ScheduleAssignmentView(a.id(), a.employmentId(), a.assignmentKey(),
                a.patternId(), pats.containsKey(a.patternId()) ? pats.get(a.patternId()).data().code() : null,
                a.ruleId(), a.validFrom(), a.validTo(), a.source(), a.status(), a.version())).toList();
    }

    /** A manual schedule for one assignment line from {@code validFrom} (to {@code validTo} exclusive, or open). */
    @AttTransactional
    public ScheduleAssignmentView assignManual(UUID employmentId, UUID assignmentKey, UUID patternId,
            LocalDate validFrom, LocalDate validTo) {
        requireEmployment(AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE, employmentId);
        if (assignmentKey == null || patternId == null || validFrom == null) {
            throw invalid("schedule-assignment-incomplete", "The assignment line, pattern and start date are required");
        }
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw invalid("invalid-period", "The end date (exclusive) must be after the start date");
        }
        List<LineVersion> line = store.lineVersions(assignmentKey);
        if (line.isEmpty() || !line.getFirst().employmentId().equals(employmentId)) {
            throw invalid("unknown-assignment-line", "Assignment line " + assignmentKey
                    + " is not a line of employment " + employmentId + " known to T&A");
        }
        LocalDate lineStart = line.getFirst().validFrom();
        LocalDate lineEnd = line.getLast().validTo();
        if (validFrom.isBefore(lineStart) || (lineEnd != null && (validTo == null || validTo.isAfter(lineEnd)))) {
            throw invalid("outside-assignment-line", "The schedule must lie within the assignment line ["
                    + lineStart + ", " + (lineEnd == null ? "open" : lineEnd) + ")");
        }
        var pattern = patterns.pattern(patternId)
                .orElseThrow(() -> invalid("unknown-pattern", "Schedule pattern " + patternId + " not found"));
        if (!"ACTIVE".equals(pattern.status())) {
            throw invalid("pattern-inactive", "Pattern " + pattern.data().code() + " is inactive");
        }
        DateRange range = new DateRange(validFrom, validTo);
        for (AssignmentRow a : store.activeAssignmentsOfKey(assignmentKey)) {
            if (!range.overlaps(new DateRange(a.validFrom(), a.validTo()))) {
                continue;
            }
            if (!"RULE".equals(a.source())) {
                throw ApiException.conflict("schedule-assignment-overlap", "A manual schedule already covers ["
                        + a.validFrom() + ", " + (a.validTo() == null ? "open" : a.validTo())
                        + "); end or cancel it first");
            }
            store.cancelAssignment(a.id());
        }
        UUID id = store.insertAssignment(employmentId, assignmentKey, patternId, null, validFrom, validTo, "MANUAL");
        generator.regenerate(assignmentKey, store.activeRules(), "MANUAL", List.of(range));
        return view(id);
    }

    /** Cancels a manual row; the rules regenerate the period. */
    @AttTransactional
    public ScheduleAssignmentView cancel(UUID employmentId, UUID scheduleAssignmentId) {
        requireEmployment(AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE, employmentId);
        AssignmentRow a = store.assignment(scheduleAssignmentId)
                .filter(x -> x.employmentId().equals(employmentId))
                .orElseThrow(() -> ApiException.notFound("Schedule assignment", scheduleAssignmentId));
        if ("RULE".equals(a.source())) {
            throw ApiException.conflict("rule-schedule-not-cancellable",
                    "A schedule produced by a rule cannot be cancelled; change the rule or add a manual schedule");
        }
        if (!"ACTIVE".equals(a.status())) {
            throw ApiException.conflict("schedule-assignment-cancelled", "The schedule is already cancelled");
        }
        store.cancelAssignment(a.id());
        generator.regenerate(a.assignmentKey(), store.activeRules(), "MANUAL",
                List.of(new DateRange(a.validFrom(), a.validTo())));
        return view(a.id());
    }

    /** Re-evaluates the rules for every line of the employment (e.g. after a back-dated People correction). */
    @AttTransactional
    public List<ScheduleAssignmentView> regenerate(UUID employmentId) {
        requireEmployment(AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE, employmentId);
        store.lineVersionsOfEmployment(employmentId).stream().map(LineVersion::assignmentKey).distinct()
                .forEach(key -> generator.regenerate(key, "RULE"));
        return list(employmentId, false);
    }

    @AttTransactional(readOnly = true)
    public List<PlannedDay> plan(UUID employmentId, LocalDate from, LocalDate to) {
        requireEmployment(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, employmentId);
        return resolver.plan(employmentId, from, to);
    }

    /**
     * STD_DAYS / STD_HOURS of an attendance period (BR-ATT-016): the period's dates and the standard-day method and
     * value Payroll published with the pay calendar ({@code att.attendance_period.std_day_method / std_day_value},
     * ATT-C-18). T&amp;A never chooses the method.
     */
    @AttTransactional(readOnly = true)
    public StandardDays.Result standardDays(UUID employmentId, UUID periodId) {
        requireEmployment(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, employmentId);
        PeriodRow p = periods.period(periodId).orElseThrow(() -> ApiException.notFound("Attendance period", periodId));
        if (p.stdDayMethod() == null) {
            throw ApiException.conflict("std-day-method-not-published", "Payroll has not published the standard-day"
                    + " method of this period yet (pay.PayCalendarPublished)");
        }
        boolean inEntity = store.lineVersionsOfEmployment(employmentId).stream()
                .anyMatch(l -> l.legalEntityId().equals(p.legalEntityId()) && !l.validFrom().isAfter(p.periodEnd())
                        && (l.validTo() == null || l.validTo().isAfter(p.periodStart())));
        if (!inEntity) {
            throw invalid("employment-not-in-period", "The employment has no assignment in the period's legal entity"
                    + " between " + p.periodStart() + " and " + p.periodEnd());
        }
        return StandardDays.compute(p.stdDayMethod(), p.stdDayValue(),
                resolver.standardDayInputs(employmentId, p.periodStart(), p.periodEnd()));
    }

    /**
     * Preview of STD_DAYS / STD_HOURS for any {@code [from, to]} with a given method and value, e.g. to check a pay
     * group set-up before its first period exists. Results handed to Payroll use {@link #standardDays(UUID, UUID)}.
     */
    @AttTransactional(readOnly = true)
    public StandardDays.Result standardDays(UUID employmentId, LocalDate from, LocalDate to, String method,
            BigDecimal value) {
        requireEmployment(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, employmentId);
        String m = ScheduleInputs.upperOrNull(method);
        if (m == null || !StandardDays.METHODS.contains(m)) {
            throw invalid("invalid-std-day-method", "The method is one of " + StandardDays.METHODS
                    + " (Payroll's pay group method, BR-PAY-009)");
        }
        if (m.startsWith("FIXED_") && (value == null || value.signum() <= 0)) {
            throw invalid("std-day-value-required", m + " needs the pay group's positive value");
        }
        return StandardDays.compute(m, m.startsWith("FIXED_") ? value : null,
                resolver.standardDayInputs(employmentId, from, to));
    }

    /** Lines of {@code legalEntityId} valid on {@code date} without a schedule, in the caller's scope (US-ATT-011). */
    @AttTransactional(readOnly = true)
    public List<Unscheduled> unscheduled(UUID legalEntityId, LocalDate date) {
        if (legalEntityId == null || date == null) {
            throw invalid("legal-entity-and-date-required", "Give the legal entity and the date");
        }
        var scope = authz.scopeFor(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, date);
        return store.unscheduledOn(legalEntityId, date, scope).stream()
                .map(l -> new Unscheduled(l.employmentId(), l.assignmentKey(), l.assignmentType(), l.orgUnitId(),
                        l.locationId(), l.validFrom()))
                .toList();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ScheduleAssignmentView view(UUID id) {
        AssignmentRow a = store.assignment(id).orElseThrow(() -> ApiException.notFound("Schedule assignment", id));
        var p = patterns.pattern(a.patternId());
        return new ScheduleAssignmentView(a.id(), a.employmentId(), a.assignmentKey(), a.patternId(),
                p.map(x -> x.data().code()).orElse(null), a.ruleId(), a.validFrom(), a.validTo(), a.source(),
                a.status(), a.version());
    }

    /** The employment must be known to T&amp;A (projection) and in the caller's scope for {@code permission}. */
    private void requireEmployment(String permission, UUID employmentId) {
        LocalDate today = clock.today();
        List<LineVersion> lines = store.lineVersionsOfEmployment(employmentId);
        LineVersion ref = lines.stream().filter(l -> l.covers(today)).findFirst()
                .orElse(lines.stream().max(Comparator.comparing(LineVersion::validFrom)).orElse(null));
        if (ref == null) {
            throw ApiException.notFound("Employment", employmentId);
        }
        if (!authz.can(permission, new AuthorizationTarget.Employee(employmentId, ref.legalEntityId(),
                ref.orgUnitId(), ref.locationId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "employment-not-in-scope",
                    "Employment " + employmentId + " is outside your data scope");
        }
    }
}
