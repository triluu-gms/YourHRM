package vn.yourhrm.attendance.internal.application.timesheet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.domain.timesheet.ProtectedBreaks;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.timesheet.MenstrualBreakStore;
import vn.yourhrm.attendance.internal.infra.timesheet.MenstrualBreakStore.DayRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.EmployeeDirectory;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Days of the paid menstrual break (BR-ATT-035, VN-16; BLLĐ 2019 Art. 137(4), ND145 Art. 80 [verify-legal]): the
 * worker declares the day herself (ESS, {@code att.menstrual_break.declare} with SELF scope) or HR records it on her
 * behalf (same permission, wider scope). No approval workflow: the law only requires the worker to notify the
 * employer. Daily processing credits {@code LABOUR.PROTECTED_BREAK.MENSTRUAL_MIN} on at most
 * {@code LABOUR.PROTECTED_BREAK.MENSTRUAL_DAYS_PER_MONTH} days per month, the larger protected break of the day applying.
 * <p><b>Privacy</b> (health data, ND13/2023): only the date is stored, never a reason, a symptom or a document; reads
 * need the declare permission (her own) or {@code att.menstrual_break.view}; a read by anybody other than the worker is
 * audited; the business audit and the workflow-free trigger carry the month or the date only inside T&amp;A, never in
 * notifications or events of other modules.
 * <p><b>Eligibility</b>: the worker is female. Gender is People's (no T&amp;A projection holds it) and is read through
 * {@code EmployeeDirectory} with the caller's rights: a recorded gender other than F refuses the declaration; when the
 * caller cannot see it (ESS without People access) the declaration is accepted with the warning
 * {@code eligibility-gender-unverified}, like the gender check of leave types.
 */
@Service
public class MenstrualBreakService {

    /** How far ahead a day may be declared. */
    static final int MAX_DAYS_AHEAD = 31;

    public record Declaration(UUID id, LocalDate workDate, String source, String status, Instant createdAt,
            Instant withdrawnAt) {

        static Declaration of(DayRow r) {
            return new Declaration(r.id(), r.workDate(), r.source(), r.status(), r.createdAt(), r.withdrawnAt());
        }
    }

    /** @param daysPerMonth the monthly number of days from the legal pack */
    public record DeclareResult(Declaration declaration, int activeInMonth, int daysPerMonth, List<String> warnings) {
    }

    public record MonthView(UUID employmentId, YearMonth month, int activeInMonth, Integer daysPerMonth,
            List<Declaration> days) {
    }

    private final MenstrualBreakStore store;
    private final TimesheetStore timesheets;
    private final PunchStore punches;
    private final AttendanceLegalParameters legal;
    private final ObjectProvider<EmployeeDirectory> directory;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public MenstrualBreakService(MenstrualBreakStore store, TimesheetStore timesheets, PunchStore punches,
            AttendanceLegalParameters legal, ObjectProvider<EmployeeDirectory> directory, AuthorizationService authz,
            AuditLog audit, BusinessClock clock, ApplicationEventPublisher events) {
        this.store = store;
        this.timesheets = timesheets;
        this.punches = punches;
        this.legal = legal;
        this.directory = directory;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    @AttTransactional
    public DeclareResult declare(UUID employmentId, LocalDate workDate) {
        if (workDate == null) {
            throw invalid("work-date-required", "The work date is required");
        }
        PunchStore.Placement where = require(AttendancePermissions.MENSTRUAL_BREAK_DECLARE, employmentId, workDate);
        if (workDate.isAfter(clock.today().plusDays(MAX_DAYS_AHEAD))) {
            throw invalid("too-far-ahead", "A day can be declared at most " + MAX_DAYS_AHEAD + " days ahead");
        }
        List<String> warnings = new ArrayList<>();
        String gender = gender(employmentId, workDate);
        if (gender == null) {
            warnings.add("eligibility-gender-unverified");
        } else if (!"F".equals(gender)) {
            throw invalid("not-eligible-gender", "The menstrual break applies to female workers (BR-ATT-035)");
        }
        requireOpen(employmentId, where, workDate);
        LegalParameters.Scope scope = new LegalParameters.Scope(where.legalEntityId(), where.locationId());
        int perMonth = requiredInt(AttendanceLegalParameters.PROTECTED_BREAK_MENSTRUAL_DAYS, scope, workDate);
        requiredInt(AttendanceLegalParameters.PROTECTED_BREAK_MENSTRUAL_MIN, scope, workDate);

        store.lockEmployment(employmentId);
        var existing = store.active(employmentId, workDate);
        YearMonth month = YearMonth.from(workDate);
        if (existing.isPresent()) { // idempotent
            int active = store.activeDates(employmentId, month.atDay(1), month.atEndOfMonth()).size();
            return new DeclareResult(Declaration.of(existing.get()), active, perMonth, warnings);
        }
        int active = store.activeDates(employmentId, month.atDay(1), month.atEndOfMonth()).size();
        if (!ProtectedBreaks.canDeclare(active, perMonth)) {
            throw ApiException.conflict("monthly-limit-reached", "At most " + perMonth
                    + " menstrual-break days can be declared per month");
        }
        String source = self(employmentId) ? "ESS" : "ADMIN";
        UUID id = store.insert(employmentId, workDate, where.legalEntityId(), where.orgUnitId(), source);
        audit.record(new BusinessAction("DECLARE", "att", "menstrual_break_day", id, where.legalEntityId(),
                Map.of("employmentId", employmentId.toString(), "month", month.toString(), "source", source)));
        events.publishEvent(new MenstrualBreakChanged(TenantContext.current().tenantId(), employmentId, workDate));
        DayRow row = store.active(employmentId, workDate).orElseThrow();
        return new DeclareResult(Declaration.of(row), active + 1, perMonth, warnings);
    }

    @AttTransactional
    public Declaration withdraw(UUID employmentId, LocalDate workDate) {
        PunchStore.Placement where = require(AttendancePermissions.MENSTRUAL_BREAK_DECLARE, employmentId, workDate);
        DayRow row = store.active(employmentId, workDate)
                .orElseThrow(() -> ApiException.notFound("Menstrual break day", workDate));
        requireOpen(employmentId, where, workDate);
        store.withdraw(row.id());
        audit.record(new BusinessAction("WITHDRAW", "att", "menstrual_break_day", row.id(), where.legalEntityId(),
                Map.of("employmentId", employmentId.toString(), "month", YearMonth.from(workDate).toString())));
        events.publishEvent(new MenstrualBreakChanged(TenantContext.current().tenantId(), employmentId, workDate));
        return store.ofMonth(employmentId, YearMonth.from(workDate)).stream().filter(r -> r.id().equals(row.id()))
                .map(Declaration::of).findFirst().orElseThrow();
    }

    /** The declarations of a month: the worker herself (declare permission) or {@code att.menstrual_break.view}. */
    @AttTransactional
    public MonthView month(UUID employmentId, YearMonth month) {
        YearMonth m = month == null ? YearMonth.from(clock.today()) : month;
        LocalDate asOf = m.atEndOfMonth().isAfter(clock.today()) ? clock.today() : m.atEndOfMonth();
        PunchStore.Placement where = punches.placement(employmentId, asOf)
                .or(() -> punches.placement(employmentId, m.atDay(1)))
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        AuthorizationTarget target = new AuthorizationTarget.Employee(employmentId, where.legalEntityId(),
                where.orgUnitId(), where.locationId());
        boolean own = self(employmentId);
        if (!own && !authz.can(AttendancePermissions.MENSTRUAL_BREAK_VIEW, target, asOf)
                && !authz.can(AttendancePermissions.MENSTRUAL_BREAK_DECLARE, target, asOf)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "employment-not-in-scope",
                    "Employment " + employmentId + " is outside your data scope");
        }
        List<Declaration> days = store.ofMonth(employmentId, m).stream().map(Declaration::of).toList();
        if (!own) {
            audit.record(new BusinessAction("VIEW", "att", "menstrual_break_day", employmentId,
                    where.legalEntityId(), Map.of("month", m.toString())));
        }
        Integer perMonth = legal.find(AttendanceLegalParameters.PROTECTED_BREAK_MENSTRUAL_DAYS,
                new LegalParameters.Scope(where.legalEntityId(), where.locationId()), asOf)
                .map(LegalParameters.Value::asInt).orElse(null);
        int active = (int) days.stream().filter(d -> "ACTIVE".equals(d.status())).count();
        return new MonthView(employmentId, m, active, perMonth, days);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * The caller's own employment: the employment-level scope of the declare permission covers it and every
     * employment of that scope belongs to the same person (the ESS rule of {@code AttendanceEssService}; a manager's
     * reports scope spans several persons and is not "self").
     */
    private boolean self(UUID employmentId) {
        try {
            var scope = authz.scopeFor(AttendancePermissions.MENSTRUAL_BREAK_DECLARE, clock.today());
            if (scope == null || !scope.employmentIds().contains(employmentId)) {
                return false;
            }
            UUID person = punches.personOf(employmentId).orElse(null);
            return person != null && scope.employmentIds().stream()
                    .allMatch(e -> person.equals(punches.personOf(e).orElse(null)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int requiredInt(String key, LegalParameters.Scope scope, LocalDate d) {
        return legal.find(key, scope, d).map(LegalParameters.Value::asInt)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "legal-parameter-missing",
                        "The legal parameter " + key + " is not defined on " + d, Map.of("parameter", key)));
    }

    /** M / F from People's directory with the caller's rights; null when not visible or not recorded. */
    private String gender(UUID employmentId, LocalDate asOf) {
        EmployeeDirectory dir = directory.getIfAvailable();
        if (dir == null) {
            return null;
        }
        try {
            return dir.snapshot(employmentId, asOf, Instant.now()).map(s -> s.person().genderCode())
                    .filter(Objects::nonNull).map(c -> c.strip().toUpperCase(Locale.ROOT))
                    .filter(c -> c.equals("M") || c.equals("F")).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void requireOpen(UUID employmentId, PunchStore.Placement where, LocalDate date) {
        UUID pg = timesheets.payGroup(employmentId, date).orElse(null);
        timesheets.periodOn(where.legalEntityId(), pg, date)
                .filter(p -> "CLOSED".equals(p.status()) || "LOCKED".equals(p.status()))
                .ifPresent(p -> {
                    throw ApiException.conflict("day-frozen", "The attendance period of " + date + " is "
                            + p.status() + "; reopen it before changing the day");
                });
    }

    private PunchStore.Placement require(String permission, UUID employmentId, LocalDate date) {
        PunchStore.Placement p = punches.placement(employmentId, date)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (!authz.can(permission, new AuthorizationTarget.Employee(employmentId, p.legalEntityId(), p.orgUnitId(),
                p.locationId()), date)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "employment-not-in-scope",
                    "Employment " + employmentId + " is outside your data scope");
        }
        return p;
    }

    static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message);
    }
}
