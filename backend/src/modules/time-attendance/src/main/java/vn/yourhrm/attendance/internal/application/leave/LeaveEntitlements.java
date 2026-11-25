package vn.yourhrm.attendance.internal.application.leave;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.WorkSchedules;
import vn.yourhrm.attendance.api.WorkSchedules.PlannedDay;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules.MonthFacts;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules.PlanDay;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.EmploymentFacts;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.JobFlags;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.LeaveSpan;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.PolicyData;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeRow;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.legal.LegalParameters.Value;

/**
 * Entitlement inputs of an employment (BR-ATT-057..059): the annual entitlement of a date (statutory floor from the
 * legal pack by the job flags of that date, the policy's base when higher, seniority steps), the planned days of a
 * range, and the per-month facts of the ND145 proration, where approved leave that the law does not count as working
 * time (type flag, or beyond the per-category cap of the leave year) reduces the worked days. Read-only; runs in the
 * caller's T&amp;A transaction.
 */
@Component
public class LeaveEntitlements {

    private final LeaveStore store;
    private final WorkSchedules schedules;
    private final AttendanceLegalParameters legal;

    public LeaveEntitlements(LeaveStore store, WorkSchedules schedules, AttendanceLegalParameters legal) {
        this.store = store;
        this.schedules = schedules;
        this.legal = legal;
    }

    /** Planned days of {@code [from, to]} as the leave rules see them. */
    public List<PlanDay> plan(UUID employmentId, LocalDate from, LocalDate to) {
        return schedules.plan(employmentId, from, to).stream()
                .map(p -> new PlanDay(p.date(), p.isWorkingDay(), p.dayValue())).toList();
    }

    public List<PlannedDay> planned(UUID employmentId, LocalDate from, LocalDate to) {
        return schedules.plan(employmentId, from, to);
    }

    /**
     * The annual entitlement as of {@code date} for a balance-tracked type: for ANNUAL the larger of the statutory floor
     * and the policy's base, plus seniority; for other types the policy's base (no floor).
     */
    public BigDecimal annual(TypeRow type, PolicyData policy, EmploymentFacts e, LocalDate date) {
        Scope scope = new Scope(e.legalEntityId(), null);
        BigDecimal floor = null;
        int stepYears = 0;
        BigDecimal stepDays = null;
        if ("ANNUAL".equals(type.data().category())) {
            JobFlags f = store.jobFlags(e.employmentId(), date);
            String key = f.minor() || f.disabled() || f.hazardous() ? AttendanceLegalParameters.LEAVE_ANNUAL_PROTECTED_DAYS
                    : AttendanceLegalParameters.LEAVE_ANNUAL_BASE_DAYS;
            floor = require(key, scope, date).asDecimal();
            stepYears = policy.seniorityStepYears() != null ? policy.seniorityStepYears()
                    : require(AttendanceLegalParameters.LEAVE_SENIORITY_STEP_YEARS, scope, date).asInt();
            stepDays = policy.seniorityStepDays() != null ? policy.seniorityStepDays()
                    : require(AttendanceLegalParameters.LEAVE_SENIORITY_STEP_DAYS, scope, date).asDecimal();
        }
        BigDecimal e1 = LeaveRules.entitlement(floor, policy.baseEntitlementDays(),
                LeaveRules.serviceYears(e.serviceStart(), date), stepYears, stepDays);
        if (e1 == null) {
            throw ApiException.unprocessable("entitlement-undefined", "Leave type " + type.data().code()
                    + " has no base entitlement in its policy " + policy.code());
        }
        return e1;
    }

    /** The statutory annual floor of the legal entity on a date (policy validation), empty when not defined. */
    public java.util.Optional<BigDecimal> annualFloor(UUID legalEntityId, LocalDate date) {
        return legal.find(AttendanceLegalParameters.LEAVE_ANNUAL_BASE_DAYS, new Scope(legalEntityId, null), date)
                .map(Value::asDecimal);
    }

    /**
     * Months of the leave year from {@code from} to {@code to} (inclusive months, clipped to the employment) that earn
     * leave under the proration rule.
     */
    public int countedMonths(EmploymentFacts e, String rule, LocalDate yearStart, LocalDate from, LocalDate to) {
        int k = 0;
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            if (counts(e, rule, yearStart, m)) {
                k++;
            }
        }
        return k;
    }

    /** Whether the month earns leave for the employment (BR-ATT-058/059). */
    public boolean counts(EmploymentFacts e, String rule, LocalDate yearStart, YearMonth m) {
        MonthFacts f = facts(e, yearStart, m);
        return f.employedDays() > 0 && LeaveRules.counts(f, rule);
    }

    /** The proration facts of a month (see {@link LeaveRules.MonthFacts}). */
    public MonthFacts facts(EmploymentFacts e, LocalDate yearStart, YearMonth m) {
        LocalDate first = m.atDay(1);
        LocalDate last = m.atEndOfMonth();
        LocalDate empFrom = e.hireDate().isAfter(first) ? e.hireDate() : first;
        LocalDate empTo = e.lastDay() != null && e.lastDay().isBefore(last) ? e.lastDay() : last;
        int calendarDays = m.lengthOfMonth();
        if (empTo.isBefore(empFrom) || "CANCELLED".equals(e.status())) {
            return new MonthFacts(m, 0, 0, calendarDays, 0);
        }
        int employedDays = (int) (empTo.toEpochDay() - empFrom.toEpochDay() + 1);
        List<PlannedDay> plan = schedules.plan(e.employmentId(), first, last);
        Set<DayOfWeek> workingDow = EnumSet.noneOf(DayOfWeek.class);
        Set<LocalDate> employedWorking = new HashSet<>();
        for (PlannedDay p : plan) {
            boolean employed = !p.date().isBefore(empFrom) && !p.date().isAfter(empTo);
            if (employed && p.scheduled() && "WORK".equals(p.dayKind())) {
                workingDow.add(p.date().getDayOfWeek());
            }
            if (employed && p.isWorkingDay()) {
                employedWorking.add(p.date());
            }
        }
        int workingDays = 0;
        for (PlannedDay p : plan) {
            boolean employed = !p.date().isBefore(empFrom) && !p.date().isAfter(empTo);
            if (employed ? p.isWorkingDay() : workingDow.contains(p.date().getDayOfWeek())) {
                workingDays++;
            }
        }
        int notService = notServiceDays(e.employmentId(), yearStart, last, employedWorking);
        return new MonthFacts(m, workingDays, Math.max(0, employedWorking.size() - notService), calendarDays,
                employedDays);
    }

    /**
     * Working days of {@code workingDays} on approved leave that do not count as working time: the type does not count,
     * or the category's cumulative calendar days in the leave year (from {@code yearStart}) exceed the legal cap.
     */
    private int notServiceDays(UUID employmentId, LocalDate yearStart, LocalDate monthEnd, Set<LocalDate> workingDays) {
        if (workingDays.isEmpty()) {
            return 0;
        }
        List<LeaveSpan> spans = store.approvedSpans(employmentId, yearStart, monthEnd);
        if (spans.isEmpty()) {
            return 0;
        }
        Map<String, Object> caps = legal.find(AttendanceLegalParameters.LEAVE_SERVICE_COUNTED_CAPS, Scope.tenant(),
                monthEnd).map(Value::asMap).orElse(Map.of());
        Map<String, Integer> used = new HashMap<>();
        Set<LocalDate> excluded = new HashSet<>();
        for (LeaveSpan s : spans) {
            for (LocalDate d = s.start().isBefore(yearStart) ? yearStart : s.start();
                    !d.isAfter(s.end()) && !d.isAfter(monthEnd); d = d.plusDays(1)) {
                boolean counted = s.countsAsService();
                Object cap = caps.get(s.category());
                if (counted && cap != null) {
                    int n = used.merge(s.category(), 1, Integer::sum);
                    counted = n <= new BigDecimal(cap.toString()).intValue();
                }
                if (!counted && workingDays.contains(d)) {
                    excluded.add(d);
                }
            }
        }
        return excluded.size();
    }

    private Value require(String key, Scope scope, LocalDate date) {
        return legal.find(key, scope, date).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "legal-parameter-missing", "The legal parameter " + key + " is not defined on " + date
                        + "; the leave entitlement cannot be computed", Map.of("parameter", key)));
    }
}
