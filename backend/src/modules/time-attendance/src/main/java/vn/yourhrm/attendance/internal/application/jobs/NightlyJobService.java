package vn.yourhrm.attendance.internal.application.jobs;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendanceJobs;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.AttendanceReminderJobs;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService.RunSummary;
import vn.yourhrm.attendance.internal.application.period.CloseHoldService;
import vn.yourhrm.attendance.internal.application.period.PeriodService;
import vn.yourhrm.attendance.internal.application.timesheet.TimesheetProcessor;
import vn.yourhrm.attendance.internal.infra.jobs.AttendanceJobStore;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * {@link AttendanceJobs}: the nightly run of T&amp;A, per tenant, as a system actor (no user, so the audit shows
 * SYSTEM), plus the platform step that keeps the partitions ahead. {@link #runAllTenants} and
 * {@link #remindAllTenants} are what the scheduler calls until the SaaS job scheduler takes over; each tenant runs in
 * its own {@code TenantContext}, so RLS confines it, and a failing tenant never stops the others.
 */
@Service
public class NightlyJobService implements AttendanceJobs {

    /** How far back the nightly recalculation reaches at most (the earliest open period otherwise). */
    static final int NIGHTLY_MAX_DAYS = 62;
    /** Without an open period the recalculation covers the last month. */
    static final int NIGHTLY_DEFAULT_DAYS = 31;
    /** Monthly leave runs (accrual, year end) on the first days of a month, so a missed night is caught up. */
    static final int MONTH_START_DAYS = 7;
    private static final String ACTOR = "att-nightly-job";
    private static final Logger log = LoggerFactory.getLogger(NightlyJobService.class);

    private final AttendanceJobStore store;
    private final PeriodService periods;
    private final TimesheetProcessor processor;
    private final LeaveBalanceService leave;
    private final CloseHoldService holds;
    private final AttendanceReminderJobs reminders;
    private final AuthorizationService authz;

    NightlyJobService(AttendanceJobStore store, PeriodService periods, TimesheetProcessor processor,
            LeaveBalanceService leave, CloseHoldService holds, AttendanceReminderJobs reminders,
            AuthorizationService authz) {
        this.store = store;
        this.periods = periods;
        this.processor = processor;
        this.leave = leave;
        this.holds = holds;
        this.reminders = reminders;
        this.authz = authz;
    }

    @Override
    public int maintainPartitions() {
        int created = store.maintainPartitions(PARTITION_MONTHS_AHEAD);
        if (created > 0) {
            log.info("T&A partitions: {} created ({} months ahead)", created, PARTITION_MONTHS_AHEAD);
        }
        return created;
    }

    @Override
    public NightlySummary runNightly(LocalDate today) {
        List<StepResult> steps = new ArrayList<>();
        steps.add(step("PERIOD_REVIEW", () -> new StepResult("PERIOD_REVIEW", periods.moveEndedPeriodsToReview(),
                List.of())));
        steps.add(step("RECALCULATION", () -> recalculate(today)));
        steps.add(step("LEAVE_CARRY_EXPIRY", () -> leaveStep("LEAVE_CARRY_EXPIRY",
                leave.systemExpireCarried(today))));
        if (today.getDayOfMonth() <= MONTH_START_DAYS) {
            YearMonth current = YearMonth.from(today);
            steps.add(step("LEAVE_ACCRUAL", () -> leaveStep("LEAVE_ACCRUAL", leave.systemAccrual(current.minusMonths(1)))));
            steps.add(step("LEAVE_ACCRUAL", () -> leaveStep("LEAVE_ACCRUAL", leave.systemAccrual(current))));
            steps.add(step("LEAVE_YEAR_END", () -> leaveStep("LEAVE_YEAR_END",
                    leave.systemYearEnd(today.getYear() - 1))));
        }
        steps.add(step("HOLD_RECONCILIATION", () -> {
            List<String> incidents = holds.reconcile();
            incidents.forEach(i -> log.warn("Close-hold reconciliation (tenant {}): {}",
                    TenantContext.current().tenantId(), i));
            return new StepResult("HOLD_RECONCILIATION", incidents.size(), incidents);
        }));
        return new NightlySummary(today, steps);
    }

    /** The manual run for the caller's tenant: {@code att.job.run} with tenant-wide scope. */
    public NightlySummary runInScope(LocalDate today) {
        if (!authz.hasTenantWidePermission(AttendancePermissions.JOB_RUN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "tenant-scope-required",
                    "Running the nightly job needs att.job.run for the whole tenant");
        }
        maintainPartitions();
        return runNightly(today);
    }

    /** Partitions, then the nightly run of every tenant with T&amp;A data. */
    public void runAllTenants(LocalDate today) {
        try {
            maintainPartitions();
        } catch (RuntimeException e) {
            log.error("T&A partition maintenance failed: {}", e.toString());
        }
        for (UUID tenant : store.tenants()) {
            inTenant(tenant, () -> {
                NightlySummary s = runNightly(today);
                if (s.failed()) {
                    log.warn("T&A nightly run of tenant {} on {} had failures: {}", tenant, today, s.steps().stream()
                            .filter(x -> !x.failures().isEmpty()).toList());
                } else {
                    log.info("T&A nightly run of tenant {} on {}: {}", tenant, today, s.steps());
                }
            });
        }
    }

    /** The morning reminders of every tenant with T&amp;A data (BR-ATT-082). */
    public void remindAllTenants(LocalDate today) {
        for (UUID tenant : store.tenants()) {
            inTenant(tenant, () -> {
                var s = reminders.runDaily(today);
                log.info("T&A reminders of tenant {} on {}: {}", tenant, today, s);
            });
        }
    }

    // ------------------------------------------------------------------------------------------------ steps

    private StepResult recalculate(LocalDate today) {
        LocalDate floor = today.minusDays(NIGHTLY_MAX_DAYS);
        LocalDate from = store.earliestOpenPeriodStart().orElse(today.minusDays(NIGHTLY_DEFAULT_DAYS));
        if (from.isBefore(floor)) {
            from = floor;
        }
        LocalDate start = from;
        List<UUID> emps = store.employmentsBetween(start, today);
        var summary = processor.recomputeAll(emps, from, today);
        return new StepResult("RECALCULATION", emps.size(), summary.failures());
    }

    private static StepResult leaveStep(String name, RunSummary r) {
        List<String> failures = r.failures().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
        return new StepResult(name, r.written(), failures);
    }

    private static StepResult step(String name, Supplier<StepResult> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            log.warn("T&A nightly step {} failed: {}", name, e.toString());
            return new StepResult(name, 0, List.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static void inTenant(UUID tenantId, Runnable r) {
        try {
            TenantContext.runWith(new TenantContext(tenantId, null, ACTOR, null, null), r);
        } catch (RuntimeException e) {
            log.error("T&A job of tenant {} failed: {}", tenantId, e.toString());
        }
    }
}
