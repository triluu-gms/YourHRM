package vn.yourhrm.attendance.internal.application.period;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePeriodClosed;
import vn.yourhrm.attendance.api.AttendancePeriodHoldGranted;
import vn.yourhrm.attendance.api.AttendancePeriodHoldReleased;
import vn.yourhrm.attendance.api.AttendancePeriodLocked;
import vn.yourhrm.attendance.api.AttendanceResults;
import vn.yourhrm.attendance.api.PayrollInbound.HeldVersion;
import vn.yourhrm.attendance.api.PayrollInbound.PayrollRunStatusChanged;
import vn.yourhrm.attendance.internal.infra.AttOutbox;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore.Period;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.common.tenancy.RequestOrigin;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * The close hold (ATT-P-17/18, BR-ATT-079/087/088, ADR-0019) and Payroll's read of results (ATT-P-01).
 * <ul>
 *   <li><b>Acquire</b>: PERIOD scope only on a CLOSED period at its current version; EMPLOYMENT scope (leaver early
 *       close) on a PUBLISHED result of a period that is not LOCKED. Idempotent by run + version; refused when another
 *       run holds it. {@code att.AttendancePeriodHoldGranted} in the same transaction.</li>
 *   <li><b>Release</b> (REJECTED / WITHDRAWN / CANCELLED / REOPEN_REQUESTED): idempotent; a reopen requested while
 *       held runs right after (BR-ATT-087).</li>
 *   <li><b>Run APPROVED</b>: consume the run's ACTIVE holds, lock the results and (PERIOD scope) the periods. Never
 *       refused: an inconsistency is recorded as a reconciliation incident (audit) and logged.</li>
 *   <li><b>Run UNLOCKED</b>: LOCKED → CLOSED with the same version, the consumed hold released (UNLOCKED), and the
 *       version announced again ({@code att.AttendancePeriodClosed}, cause UNLOCK). REVERSED changes nothing.</li>
 * </ul>
 */
@Service
public class CloseHoldService implements AttendanceResults {

    private static final Logger log = LoggerFactory.getLogger(CloseHoldService.class);

    private final AttendancePeriodStore periods;
    private final AttendanceResultStore results;
    private final PeriodService periodService;
    private final AttOutbox outbox;
    private final AuditLog audit;

    public CloseHoldService(AttendancePeriodStore periods, AttendanceResultStore results, PeriodService periodService,
            AttOutbox outbox, AuditLog audit) {
        this.periods = periods;
        this.results = results;
        this.periodService = periodService;
        this.outbox = outbox;
        this.audit = audit;
    }

    // ------------------------------------------------------------------------------------------------ reads

    @Override
    @AttTransactional(readOnly = true)
    public Optional<Result> currentResult(UUID periodId, UUID employmentId) {
        return results.live(periodId, employmentId);
    }

    @Override
    @AttTransactional(readOnly = true)
    public Optional<Result> result(UUID resultId) {
        return results.result(resultId);
    }

    @Override
    @AttTransactional(readOnly = true)
    public List<Line> lines(UUID resultId) {
        return results.lines(resultId, null);
    }

    @Override
    @AttTransactional(readOnly = true)
    public Optional<Hold> activeHold(UUID periodId, UUID employmentId) {
        return results.activeHold(periodId, employmentId);
    }

    // ------------------------------------------------------------------------------------------------ hold

    @Override
    @AttTransactional
    public HoldOutcome acquireHold(UUID periodId, int resultVersion, UUID payrollRunId, UUID employmentId) {
        Optional<Period> found = periods.forUpdate(periodId);
        if (found.isEmpty()) {
            return HoldOutcome.refused("period-not-found", "No attendance period " + periodId);
        }
        Period p = found.get();
        Optional<Hold> active = results.activeHold(periodId, employmentId);
        if (active.isPresent()) {
            Hold h = active.get();
            if (h.payrollRunId().equals(payrollRunId) && h.resultVersion() == resultVersion) {
                return new HoldOutcome(true, null, null, h);    // idempotent
            }
            return HoldOutcome.refused("held-by-other-run", "Payroll run " + h.payrollRunId() + " holds v"
                    + h.resultVersion() + " of this scope");
        }
        if ("LOCKED".equals(p.status())) {
            return HoldOutcome.refused("period-locked", "The period is LOCKED");
        }
        if (employmentId == null) {
            if (!"CLOSED".equals(p.status())) {
                return HoldOutcome.refused("period-not-closed", "The period is " + p.status()
                        + "; only a CLOSED period can be held");
            }
            if (p.currentVersion() != resultVersion) {
                return HoldOutcome.refused("version-not-current", "The current version is v" + p.currentVersion()
                        + ", not v" + resultVersion);
            }
        }
        Optional<Result> live = results.live(periodId, employmentId);
        if (live.isEmpty()) {
            return HoldOutcome.refused("result-not-found", "No published result for this scope");
        }
        Result r = live.get();
        if (!"PUBLISHED".equals(r.status()) || r.version() != resultVersion) {
            return HoldOutcome.refused("version-not-current", "The current version is v" + r.version() + " ("
                    + r.status() + "), not v" + resultVersion);
        }
        String scope = employmentId == null ? "PERIOD" : "EMPLOYMENT";
        UUID holdId = results.insertHold(periodId, r.id(), resultVersion, scope, employmentId, payrollRunId,
                RequestOrigin.optional().map(RequestOrigin::correlationId).orElse(null));
        outbox.publish(new AttendancePeriodHoldGranted(tenant(), p.legalEntityId(), holdId, periodId,
                p.payGroupId(), p.periodStart(), r.id(), resultVersion, scope, employmentId, payrollRunId));
        return new HoldOutcome(true, null, null, results.hold(holdId).orElseThrow());
    }

    @Override
    @AttTransactional
    public HoldOutcome releaseHold(UUID periodId, UUID payrollRunId, String reason, UUID employmentId) {
        if (reason == null || !RELEASE_REASONS.contains(reason)) {
            return HoldOutcome.refused("invalid-reason", "The reason is one of " + RELEASE_REASONS);
        }
        Optional<Period> found = periods.forUpdate(periodId);
        if (found.isEmpty()) {
            return HoldOutcome.refused("period-not-found", "No attendance period " + periodId);
        }
        List<Hold> holds = results.holdsOfRun(periodId, payrollRunId, employmentId);
        if (holds.isEmpty()) {
            return HoldOutcome.refused("hold-not-found", "Payroll run " + payrollRunId + " holds nothing here");
        }
        Hold latest = holds.getFirst();
        switch (latest.status()) {
            case "RELEASED" -> {
                return new HoldOutcome(true, null, null, latest);   // idempotent
            }
            case "CONSUMED" -> {
                return HoldOutcome.refused("hold-consumed", "The run was approved and the version is LOCKED; only"
                        + " a payroll unlock gives it back (BR-ATT-088)");
            }
            default -> {
            }
        }
        release(found.get(), latest, reason);
        return new HoldOutcome(true, null, null, results.hold(latest.id()).orElseThrow());
    }

    private void release(Period p, Hold h, String reason) {
        results.releaseHold(h.id(), reason);
        outbox.publish(new AttendancePeriodHoldReleased(tenant(), p.legalEntityId(), h.id(), p.id(),
                p.periodStart(), h.resultId(), h.resultVersion(), h.scope(), h.employmentId(), h.payrollRunId(),
                reason));
        if ("PERIOD".equals(h.scope())) {
            periodService.reopenAfterRelease(p.id());
        }
    }

    // ------------------------------------------------------------------------------------------------ run status

    /** @return true when something changed */
    @AttTransactional
    public boolean runStatusChanged(PayrollRunStatusChanged e) {
        String status = e.status() == null ? "" : e.status();
        return switch (status) {
            case "APPROVED" -> approved(e);
            case "UNLOCKED" -> unlocked(e);
            case "REJECTED", "WITHDRAWN", "CANCELLED" -> {
                boolean any = false;
                for (Hold h : results.holdsOfRun(e.payrollRunId(), "ACTIVE")) {
                    release(periods.forUpdate(h.periodId()).orElseThrow(), h, status);
                    any = true;
                }
                yield any;
            }
            default -> false;   // REVERSED and the other statuses do not touch T&A (gap GAP-028)
        };
    }

    private boolean approved(PayrollRunStatusChanged e) {
        boolean any = false;
        List<Hold> active = results.holdsOfRun(e.payrollRunId(), "ACTIVE");
        for (Hold h : active) {
            Period p = periods.forUpdate(h.periodId()).orElseThrow();
            results.consumeHold(h.id());
            results.lock(h.resultId(), e.payrollRunId());
            if ("PERIOD".equals(h.scope())) {
                if ("CLOSED".equals(p.status()) && p.currentVersion() == h.resultVersion()) {
                    periods.lock(p.id(), h.resultVersion(), e.payrollRunId());
                } else {
                    incident(p, e, "period " + p.status() + " v" + p.currentVersion() + " while the run held v"
                            + h.resultVersion());
                }
            }
            outbox.publish(new AttendancePeriodLocked(tenant(), p.legalEntityId(), p.id(), p.payGroupId(),
                    p.periodStart(), h.resultId(), h.resultVersion(), h.scope(), h.employmentId(),
                    e.payrollRunId()));
            any = true;
        }
        // what Payroll says it consumed must be what T&A locked
        for (HeldVersion v : e.heldVersions()) {
            boolean matched = results.holdsOfRun(v.periodId(), e.payrollRunId(), v.employmentId()).stream()
                    .anyMatch(h -> h.resultVersion() == v.resultVersion() && "CONSUMED".equals(h.status()));
            if (!matched) {
                periods.find(v.periodId()).ifPresentOrElse(
                        p -> incident(p, e, "no consumed hold of v" + v.resultVersion() + " for this run"),
                        () -> log.warn("Run {} approved with unknown attendance period {}", e.payrollRunId(),
                                v.periodId()));
            }
        }
        return any;
    }

    private boolean unlocked(PayrollRunStatusChanged e) {
        boolean any = false;
        for (Hold h : results.holdsOfRun(e.payrollRunId(), "CONSUMED")) {
            Period p = periods.forUpdate(h.periodId()).orElseThrow();
            results.releaseHold(h.id(), "UNLOCKED");
            results.unlock(h.resultId(), e.payrollRunId());
            if ("PERIOD".equals(h.scope()) && "LOCKED".equals(p.status())) {
                periods.unlock(p.id());
            }
            outbox.publish(new AttendancePeriodHoldReleased(tenant(), p.legalEntityId(), h.id(), p.id(),
                    p.periodStart(), h.resultId(), h.resultVersion(), h.scope(), h.employmentId(), e.payrollRunId(),
                    "UNLOCKED"));
            Result r = results.result(h.resultId()).orElseThrow();
            outbox.publish(new AttendancePeriodClosed(tenant(), p.legalEntityId(), p.id(), p.payGroupId(),
                    p.periodStart(), p.periodEnd(), r.id(), r.version(), h.scope(), h.employmentId(), "UNLOCK",
                    r.checksum(), r.lineCount() == null ? 0 : r.lineCount(), 0));
            any = true;
        }
        return any;
    }

    private void incident(Period p, PayrollRunStatusChanged e, String what) {
        log.warn("Close-hold reconciliation incident: run {} {} on period {}: {}", e.payrollRunId(), e.status(),
                p.id(), what);
        Map<String, String> d = new LinkedHashMap<>();
        d.put("payrollRunId", e.payrollRunId().toString());
        d.put("runStatus", String.valueOf(e.status()));
        d.put("incident", what);
        audit.record(new BusinessAction("RECONCILIATION_INCIDENT", "att", "attendance_period", p.id(),
                p.legalEntityId(), d));
    }

    // ------------------------------------------------------------------------------------------------ reconcile

    /**
     * Nightly reconciliation (BR-ATT-088) of the T&amp;A side: every ACTIVE hold sits on a PUBLISHED version of a
     * CLOSED period (PERIOD scope), every CONSUMED hold on a LOCKED version (and a LOCKED period at that version). The
     * comparison with Payroll's run states needs the Payroll module.
     */
    @AttTransactional(readOnly = true)
    public List<String> reconcile() {
        List<String> out = new ArrayList<>();
        for (Hold h : results.openHolds()) {
            Result r = results.result(h.resultId()).orElse(null);
            Period p = periods.find(h.periodId()).orElse(null);
            if (r == null || p == null) {
                out.add("hold " + h.id() + ": result or period missing");
                continue;
            }
            if ("ACTIVE".equals(h.status())) {
                if (!"PUBLISHED".equals(r.status())) {
                    out.add("hold " + h.id() + " ACTIVE on a " + r.status() + " result");
                }
                if ("PERIOD".equals(h.scope()) && !("CLOSED".equals(p.status())
                        && p.currentVersion() == h.resultVersion())) {
                    out.add("hold " + h.id() + " ACTIVE on v" + h.resultVersion() + " but the period is "
                            + p.status() + " v" + p.currentVersion());
                }
            } else {
                if (!"LOCKED".equals(r.status())) {
                    out.add("hold " + h.id() + " CONSUMED but the result is " + r.status());
                }
                if ("PERIOD".equals(h.scope()) && !("LOCKED".equals(p.status())
                        && Integer.valueOf(h.resultVersion()).equals(p.lockedVersion()))) {
                    out.add("hold " + h.id() + " CONSUMED v" + h.resultVersion() + " but the period is "
                            + p.status() + " locked v" + p.lockedVersion());
                }
            }
        }
        return out;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
