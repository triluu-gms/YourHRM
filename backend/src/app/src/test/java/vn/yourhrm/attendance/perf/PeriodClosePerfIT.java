package vn.yourhrm.attendance.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.attendance.api.AttendanceResults.Result;
import vn.yourhrm.attendance.internal.application.period.PeriodService;
import vn.yourhrm.attendance.internal.application.timesheet.TimesheetProcessor;

/**
 * NFR-ATT-003: closing a period of 10,000 employees (final processing + snapshot) finishes within 15 minutes.
 * On a generated tenant whose previous month (P1) is OPEN and not processed yet, measures
 * <ol>
 *   <li>final processing: {@code TimesheetProcessor.recomputeAll} of every employment over the period;</li>
 *   <li>the close: {@code PeriodService.close} (checklist, segments and category lines, result version 1 with its
 *       lines in one transaction, period CLOSED, outbox event, audit), every waivable checklist item waived;</li>
 * </ol>
 * and asserts their sum against the scaled budget.
 */
@Tag("perf")
class PeriodClosePerfIT extends PerfTestBase {

    static final Duration BUDGET_10K = Duration.ofMinutes(15);
    static final List<String> WAIVE_ALL = List.of("PERIOD_NOT_ENDED", "NOT_PROCESSED", "MISSING_PUNCH",
            "ABSENT_NO_CODE", "QUARANTINED_PUNCH", "OT_CAP", "PENDING_LEAVE", "PENDING_OVERTIME",
            "PENDING_CORRECTION");

    @Autowired
    TimesheetProcessor processor;

    @Autowired
    PeriodService periods;

    @Test
    void periodCloseMeetsTheBudget() throws Exception {
        PerfTenantGenerator.Tenant t = seed("perf-close");
        Duration budget = scaled(BUDGET_10K, t.employments());

        long t0 = System.nanoTime();
        List<UUID> emps = t.asSystem(() -> processor.employmentsOf(t.legalEntity(), t.p1Start(), t.p1End()));
        TimesheetProcessor.Summary processed = t.asSystem(() -> processor.recomputeAll(emps, t.p1Start(),
                t.p1End()));
        Duration processing = since(t0);
        log("NFR-ATT-003", "final processing", processing, null, "employments=" + emps.size() + " written="
                + processed.written() + " unchanged=" + processed.unchanged() + " failures="
                + processed.failures().size());

        long t1 = System.nanoTime();
        Result result = t.asAdmin(() -> periods.close(t.periodP1(), WAIVE_ALL, "perf test: synthetic exceptions"));
        Duration close = since(t1);
        Map<String, Object> header = ADMIN.queryForMap("""
                select r.line_count, r.status, p.status as period_status
                  from att.attendance_result r join att.attendance_period p on p.tenant_id = r.tenant_id
                       and p.id = r.period_id where r.id = ?""", result.id());
        log("NFR-ATT-003", "close + snapshot", close, null, "lines=" + header.get("line_count"));
        Duration total = processing.plus(close);
        log("NFR-ATT-003", "total", total, budget, null);

        assertThat(emps).hasSize((int) t.employments());
        assertThat(processed.failures()).as("processing failures").isEmpty();
        assertThat(header).containsEntry("status", "PUBLISHED").containsEntry("period_status", "CLOSED");
        assertThat(((Number) header.get("line_count")).longValue()).isGreaterThan(t.employments());
        assertWithin("NFR-ATT-003", total, budget);
    }
}
