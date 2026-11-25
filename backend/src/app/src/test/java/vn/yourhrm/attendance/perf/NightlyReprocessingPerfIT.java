package vn.yourhrm.attendance.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.api.AttendanceJobs.NightlySummary;
import vn.yourhrm.attendance.api.AttendanceJobs.StepResult;

/**
 * NFR-ATT-002 (Phase-1 part): the nightly full reprocessing of a 10,000-employee tenant finishes within 30 minutes.
 * Measures {@code AttendanceJobs.runNightly} (period review, RECALCULATION = {@code TimesheetProcessor.recomputeAll}
 * of every employment from the earliest open period to today, leave steps, hold reconciliation) on a generated
 * tenant, first with every day new (worst case: every day is written), then again with unchanged inputs (the usual
 * night: inputs hash equal, nothing written). The first run is asserted against the scaled budget.
 */
@Tag("perf")
class NightlyReprocessingPerfIT extends PerfTestBase {

    static final Duration BUDGET_10K = Duration.ofMinutes(30);

    @Test
    void nightlyFullReprocessingMeetsTheBudget() throws Exception {
        PerfTenantGenerator.Tenant t = seed("perf-nightly");
        Duration budget = scaled(BUDGET_10K, t.employments());

        long t0 = System.nanoTime();
        NightlySummary first = t.asSystem(() -> jobs.runNightly(t.today()));
        Duration cold = since(t0);
        StepResult recalc = recalculation(first);
        long days = ADMIN.queryForObject("select count(*) from att.daily_timesheet where tenant_id = ?", Long.class,
                t.tenant());
        log("NFR-ATT-002", "nightly (all days new)", cold, budget, "employments=" + recalc.count()
                + " timesheetDays=" + days + " failures=" + recalc.failures().size() + " steps=" + first.steps());

        long t1 = System.nanoTime();
        NightlySummary second = t.asSystem(() -> jobs.runNightly(t.today()));
        Duration warm = since(t1);
        log("NFR-ATT-002", "nightly (inputs unchanged)", warm, budget, "failures="
                + recalculation(second).failures().size());

        assertThat(recalc.count()).isEqualTo((int) t.employments());
        assertThat(recalc.failures()).as("recalculation failures").isEmpty();
        assertThat(days).as("timesheet days written").isGreaterThan(t.employments() * 20);
        assertWithin("NFR-ATT-002", cold, budget);
        assertWithin("NFR-ATT-002", warm, budget);
    }

    private static StepResult recalculation(NightlySummary s) {
        return s.steps().stream().filter(x -> "RECALCULATION".equals(x.step())).findFirst().orElseThrow();
    }
}
