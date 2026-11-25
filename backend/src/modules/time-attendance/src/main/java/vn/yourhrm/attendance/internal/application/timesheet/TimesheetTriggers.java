package vn.yourhrm.attendance.internal.application.timesheet;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.attendance.api.AbsenceChanged;
import vn.yourhrm.attendance.api.HolidayCalendarChanged;
import vn.yourhrm.attendance.api.ScheduleAssignmentChanged;
import vn.yourhrm.attendance.internal.application.punch.PunchesMatched;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.core.api.CoreEvents.ImportCommitted;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.HistoryEntry;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/**
 * What makes daily processing run (BR-ATT-038, US-ATT-030), each after the triggering change committed and in its own
 * T&amp;A transactions: a committed punch import, punches released from quarantine, a schedule change
 * ({@code att.ScheduleAssignmentChanged}, which also covers hires, leavers and assignment changes from People) and a
 * holiday calendar change; plus the outcome of a correction approval. Back-dated changes are recomputed at most
 * {@link #MAX_LOOKBACK_DAYS} days back; older days need the explicit recalculation endpoint. Failures are logged and
 * never break the triggering flow. The nightly full recalculation (US-ATT-031, NFR-ATT-002) needs the platform job
 * scheduler and is not built yet.
 */
@Component
class TimesheetTriggers {

    private static final Logger log = LoggerFactory.getLogger(TimesheetTriggers.class);
    static final int MAX_LOOKBACK_DAYS = 366;

    private final TimesheetProcessor processor;
    private final TimesheetStore store;
    private final CorrectionService corrections;
    private final WorkflowService workflow;
    private final BusinessClock clock;
    private final TransactionTemplate readTx;

    TimesheetTriggers(TimesheetProcessor processor, TimesheetStore store, CorrectionService corrections,
            WorkflowService workflow, BusinessClock clock, @Qualifier(AttDb.TX) PlatformTransactionManager attTm) {
        this.processor = processor;
        this.store = store;
        this.corrections = corrections;
        this.workflow = workflow;
        this.clock = clock;
        this.readTx = new TransactionTemplate(attTm);
        this.readTx.setReadOnly(true);
    }

    @EventListener
    void onImport(ImportCommitted e) {
        if (!"att".equals(e.ownerModule()) || e.importType() == null || !e.importType().startsWith("ATT_PUNCH_")) {
            return;
        }
        inTenant(e.tenantId(), () -> {
            List<Object[]> days = readTx.execute(s -> store.punchDaysOfImport(e.jobId()));
            for (Object[] d : days) {
                run((UUID) d[0], ((LocalDate) d[1]).minusDays(1), ((LocalDate) d[2]).plusDays(1));
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onMatched(PunchesMatched e) {
        inTenant(e.tenantId(), () -> e.ranges().forEach((emp, r) -> run(emp, r[0].minusDays(1), r[1].plusDays(1))));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onSchedule(ScheduleAssignmentChanged e) {
        if (e.employmentId() == null || e.rangeFrom() == null) {
            return;
        }
        inTenant(e.tenantId(), () -> run(e.employmentId(), e.rangeFrom(),
                e.rangeTo() == null ? clock.today() : e.rangeTo()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onHoliday(HolidayCalendarChanged e) {
        if (e.changedDates().isEmpty() || e.legalEntityIds().isEmpty()) {
            return;
        }
        LocalDate from = e.changedDates().stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate to = e.changedDates().stream().max(LocalDate::compareTo).orElseThrow();
        inTenant(e.tenantId(), () -> {
            List<UUID> emps = new ArrayList<>();
            for (UUID le : e.legalEntityIds()) {
                emps.addAll(readTx.execute(s -> store.employmentsOfLegalEntity(le, from, to)));
            }
            // substitute days may move to the next working days: recompute a week past the last change
            all(emps.stream().distinct().toList(), from, to.plusDays(7));
        });
    }

    /** Approved, withdrawn or shortened leave (BR-ATT-061/065): the days of the leave are recomputed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onAbsence(AbsenceChanged e) {
        LocalDate today = clock.today();
        if (e.startDate().isAfter(today)) {
            return; // future days are computed when they come
        }
        inTenant(e.tenantId(), () -> run(e.employmentId(), e.startDate(),
                e.endDate().isAfter(today) ? today : e.endDate()));
    }

    @EventListener
    void onWorkflow(WorkflowCompleted e) {
        if (!TimesheetCatalogues.PROCESS_CORRECTION.equals(e.processTypeCode())) {
            return;
        }
        UUID decider = e.history().stream().filter(h -> h.stepNo() != null).reduce((a, b) -> b)
                .map(HistoryEntry::actorUserId).orElse(null);
        try {
            Map<String, Object> result = corrections.applyOutcome(e.subjectId(), e.outcome(), decider);
            workflow.acknowledgeCallback(e.callbackId(), result);
        } catch (RuntimeException ex) {
            log.warn("Applying {} outcome {} to correction {} failed: {}", e.processTypeCode(), e.outcome(),
                    e.subjectId(), ex.getMessage());
            workflow.reportCallbackFailure(e.callbackId(), ex.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void run(UUID employmentId, LocalDate from, LocalDate to) {
        LocalDate floor = clock.today().minusDays(MAX_LOOKBACK_DAYS);
        LocalDate f = from.isBefore(floor) ? floor : from;
        if (f.isAfter(to)) {
            return;
        }
        try {
            var s = processor.recompute(employmentId, f, to);
            if (!s.failures().isEmpty()) {
                log.warn("Daily processing of {} [{}, {}]: {}", employmentId, f, to, s.failures());
            }
        } catch (RuntimeException ex) {
            log.warn("Daily processing of {} [{}, {}] failed: {}", employmentId, f, to, ex.toString());
        }
    }

    private void all(Collection<UUID> emps, LocalDate from, LocalDate to) {
        emps.forEach(e -> run(e, from, to));
    }

    private static void inTenant(UUID tenantId, Runnable r) {
        if (TenantContext.isBound() && TenantContext.current().tenantId().equals(tenantId)) {
            r.run();
        } else {
            TenantContext.runWith(new TenantContext(tenantId, null, "att-processing", null, null), r);
        }
    }
}
