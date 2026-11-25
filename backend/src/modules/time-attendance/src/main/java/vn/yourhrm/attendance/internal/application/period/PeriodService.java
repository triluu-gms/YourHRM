package vn.yourhrm.attendance.internal.application.period;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePeriodClosed;
import vn.yourhrm.attendance.api.AttendancePeriodReopened;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.AttendanceResults.Hold;
import vn.yourhrm.attendance.api.AttendanceResults.Line;
import vn.yourhrm.attendance.api.AttendanceResults.Result;
import vn.yourhrm.attendance.api.LeaveSettlements;
import vn.yourhrm.attendance.api.LeaverAttendanceClosed;
import vn.yourhrm.attendance.api.RetroDeltaCreated;
import vn.yourhrm.attendance.internal.application.approval.ApprovalCatalogues;
import vn.yourhrm.attendance.internal.application.period.PeriodLines.Built;
import vn.yourhrm.attendance.internal.application.period.PeriodLines.Retro;
import vn.yourhrm.attendance.internal.domain.period.ResultLines;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel;
import vn.yourhrm.attendance.internal.infra.AttOutbox;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.approval.ChangeRequestStore;
import vn.yourhrm.attendance.internal.infra.approval.ChangeRequestStore.ChangeRequest;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore.Period;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.Count;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.NewLine;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.NewResult;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.RetroCandidate;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Audience;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Notifications.SendRequest;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;

/**
 * Attendance periods and their close (BRD §4.8, P8/P9):
 * <ul>
 *   <li><b>Lifecycle</b> (BR-ATT-073): OPEN → IN_REVIEW (manually, or the day after the period end by
 *       {@link #moveEndedPeriodsToReview}) → CLOSED → LOCKED (Payroll approval only). Reopen CLOSED → OPEN needs
 *       {@code att.period.reopen} and a reason; it withdraws the current version.</li>
 *   <li><b>Checklist</b> (BR-ATT-074): blocking items must be resolved or waived by name with a reason. A missing
 *       standard-day method cannot be waived.</li>
 *   <li><b>Close</b> (BR-ATT-075/076): per legal entity x pay group; the result version holds every employment with
 *       days of the period (except leavers closed early), per assignment segment, plus the retro deltas of LOCKED
 *       periods of the same legal entity x pay group (BR-ATT-071). Header and lines in one transaction (sealed at
 *       commit), {@code att.AttendancePeriodClosed} and one {@code att.RetroDeltaCreated} per corrected period.</li>
 *   <li><b>Early close</b> of a leaver (BR-ATT-075): an EMPLOYMENT-scope EARLY_CLOSE result up to the last working
 *       day, {@code att.LeaverAttendanceClosed}.</li>
 *   <li><b>Held reopen</b> (BR-ATT-087): recorded on the period, the payroll administrators are notified, and the
 *       reopen runs when Payroll releases the hold ({@link #reopenAfterRelease}).</li>
 *   <li><b>Approved reopen</b> (ATT-P-11, {@code att.PERIOD_REOPEN}): when the legal entity's setting
 *       {@code att.period_reopen_approval} is on, the reopen is a request in Core's workflow and runs on the final
 *       approval ({@link #applyReopenOutcome}); the approval comes first, then the held-reopen rule above.</li>
 * </ul>
 */
@Service
public class PeriodService {

    /** Checklist codes that can never be waived. */
    static final Set<String> NOT_WAIVABLE = Set.of("STD_METHOD_MISSING");
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record ChecklistItem(String code, long count, boolean blocking, boolean waivable) {
    }

    /** @param employmentId null for the period scope */
    public record Checklist(UUID periodId, UUID employmentId, LocalDate dateFrom, LocalDate dateTo, boolean canClose,
            List<ChecklistItem> items) {
    }

    public record PeriodView(UUID id, UUID legalEntityId, UUID payGroupId, LocalDate periodStart, LocalDate periodEnd,
            LocalDate otPeriodStart, LocalDate otPeriodEnd, String stdDayMethod, java.math.BigDecimal stdDayValue,
            LocalDate closeDeadline, String status, int currentVersion, Integer lockedVersion,
            java.time.Instant closedAt, java.time.Instant reopenedAt, String reopenReason,
            java.time.Instant reopenRequestedAt, String reopenRequestReason, Hold activeHold, int version) {
    }

    /**
     * @param outcome           REOPENED; RELEASE_REQUESTED when Payroll holds the period; APPROVAL_REQUESTED when the
     *                          reopen waits for the workflow ({@code att.period_reopen_approval})
     * @param approvalRequestId the {@code att.change_request} of an approval, else null
     */
    public record ReopenOutcome(String outcome, PeriodView period, Hold hold, UUID approvalRequestId) {

        public ReopenOutcome(String outcome, PeriodView period, Hold hold) {
            this(outcome, period, hold, null);
        }
    }

    private final AttendancePeriodStore periods;
    private final AttendanceResultStore results;
    private final PeriodLines lines;
    private final PunchStore employments;
    private final AttOutbox outbox;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final Notifications notifications;
    private final BusinessClock clock;
    private final ObjectProvider<LeaveSettlements> settlements;
    private final ChangeRequestStore changeRequests;
    private final ApprovalCatalogues approvals;
    private final WorkflowService workflow;

    public PeriodService(AttendancePeriodStore periods, AttendanceResultStore results, PeriodLines lines,
            PunchStore employments, AttOutbox outbox, AuthorizationService authz, AuditLog audit,
            Notifications notifications, BusinessClock clock, ObjectProvider<LeaveSettlements> settlements,
            ChangeRequestStore changeRequests, ApprovalCatalogues approvals, WorkflowService workflow) {
        this.periods = periods;
        this.results = results;
        this.lines = lines;
        this.employments = employments;
        this.outbox = outbox;
        this.authz = authz;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
        this.settlements = settlements;
        this.changeRequests = changeRequests;
        this.approvals = approvals;
        this.workflow = workflow;
    }

    // ------------------------------------------------------------------------------------------------ reads

    @AttTransactional(readOnly = true)
    public List<PeriodView> list(UUID legalEntityId, UUID payGroupId, String status, LocalDate from, LocalDate to) {
        return periods.list(legalEntityId, payGroupId, status, from, to).stream()
                .filter(p -> can(AttendancePermissions.PERIOD_VIEW, p.legalEntityId()))
                .map(this::view).toList();
    }

    @AttTransactional(readOnly = true)
    public PeriodView get(UUID id) {
        return view(require(AttendancePermissions.PERIOD_VIEW, periods.find(id), id));
    }

    @AttTransactional(readOnly = true)
    public List<Result> results(UUID periodId) {
        require(AttendancePermissions.PERIOD_VIEW, periods.find(periodId), periodId);
        return results.results(periodId);
    }

    @AttTransactional(readOnly = true)
    public Result result(UUID resultId) {
        Result r = results.result(resultId).orElseThrow(() -> ApiException.notFound("Attendance result", resultId));
        require(AttendancePermissions.PERIOD_VIEW, periods.find(r.periodId()), r.periodId());
        return r;
    }

    @AttTransactional(readOnly = true)
    public List<Line> lines(UUID resultId, UUID employmentId) {
        result(resultId);
        return results.lines(resultId, employmentId);
    }

    @AttTransactional(readOnly = true)
    public List<Hold> holds(UUID periodId) {
        require(AttendancePermissions.PERIOD_VIEW, periods.find(periodId), periodId);
        return results.holdsOfPeriod(periodId);
    }

    @AttTransactional(readOnly = true)
    public List<RetroCandidate> pendingRetro(UUID periodId) {
        require(AttendancePermissions.PERIOD_VIEW, periods.find(periodId), periodId);
        return results.retroRequests(periodId);
    }

    @AttTransactional(readOnly = true)
    public Checklist checklist(UUID periodId, UUID employmentId) {
        Period p = require(AttendancePermissions.PERIOD_VIEW, periods.find(periodId), periodId);
        LocalDate to = employmentId == null ? p.periodEnd() : leaverEnd(p, employmentId);
        return checklist(p, employmentId, to, Set.of());
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    /** OPEN → IN_REVIEW (BR-ATT-073). */
    @AttTransactional
    public PeriodView review(UUID id) {
        Period p = require(AttendancePermissions.PERIOD_CLOSE, periods.forUpdate(id), id);
        if (!"OPEN".equals(p.status())) {
            throw ApiException.conflict("period-not-open", "Only an OPEN period moves to review (it is " + p.status()
                    + ")");
        }
        periods.toStatus(id, "IN_REVIEW");
        audit.record(new BusinessAction("REVIEW", "att", "attendance_period", id, p.legalEntityId(), Map.of()));
        return view(periods.find(id).orElseThrow());
    }

    /** The day after its end an OPEN period goes to IN_REVIEW (gap §7.2 "D+1"); a job of the current tenant. */
    @AttTransactional
    public int moveEndedPeriodsToReview() {
        List<UUID> ids = periods.endedOpen(clock.today());
        ids.forEach(id -> periods.toStatus(id, "IN_REVIEW"));
        return ids.size();
    }

    /**
     * Closes (or re-closes) the period: checklist, lines, retro deltas, new version, CLOSED, events.
     *
     * @param waive       checklist codes waived explicitly (BR-ATT-074)
     * @param waiveReason required when something present is waived
     */
    @AttTransactional
    public Result close(UUID id, List<String> waive, String waiveReason) {
        Period p = require(AttendancePermissions.PERIOD_CLOSE, periods.forUpdate(id), id);
        if (!"OPEN".equals(p.status()) && !"IN_REVIEW".equals(p.status())) {
            throw ApiException.conflict("period-not-open", "The period is " + p.status()
                    + "; reopen it to close it again");
        }
        Set<String> waived = waivers(waive);
        periods.attachDays(p);
        checkClosable(checklist(p, null, p.periodEnd(), waived), waived, waiveReason);

        Set<UUID> early = new HashSet<>(results.earlyClosed(p.id()));
        Built built = lines.regular(p, null, p.periodEnd(), early);
        List<RetroCandidate> candidates = results.retroCandidates(p.legalEntityId(), p.payGroupId(),
                p.periodStart(), null).stream().filter(c -> built.employments().contains(c.employmentId())).toList();
        List<Retro> retro = lines.retro(candidates);

        int version = p.currentVersion() + 1;
        UUID supersedes = results.results(p.id()).stream().filter(r -> "PERIOD".equals(r.scope()))
                .map(Result::id).findFirst().orElse(null);
        results.live(p.id(), null).ifPresent(r -> results.withdraw(r.id(), "superseded by v" + version));
        String cause = p.currentVersion() == 0 ? "CLOSE" : "RECLOSE";
        UUID resultId = publish(p, null, "REGULAR", version, p.periodEnd(), built, retro, supersedes, cause);
        periods.close(p.id(), version);

        int retroLines = retro.stream().mapToInt(r -> r.lines().size()).sum();
        int lineCount = built.lines().size() + retroLines;
        outbox.publish(new AttendancePeriodClosed(tenant(), p.legalEntityId(), p.id(), p.payGroupId(),
                p.periodStart(), p.periodEnd(), resultId, version, "PERIOD", null, cause,
                results.result(resultId).orElseThrow().checksum(), lineCount, retroLines));
        retroEvents(p, resultId, version, retro);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("version", String.valueOf(version));
        details.put("cause", cause);
        details.put("lines", String.valueOf(lineCount));
        details.put("retroLines", String.valueOf(retroLines));
        if (!waived.isEmpty()) {
            details.put("waived", String.join(",", waived));
            details.put("waiveReason", waiveReason);
        }
        audit.record(new BusinessAction("CLOSE", "att", "attendance_period", p.id(), p.legalEntityId(), details));
        return results.result(resultId).orElseThrow();
    }

    /**
     * Early close of a leaver (BR-ATT-075, R3-06): EMPLOYMENT-scope EARLY_CLOSE result of the days up to the last
     * working day; the employment is then left out of the period result. Repeating it publishes a new version (the
     * previous one must not be held).
     */
    @AttTransactional
    public Result earlyClose(UUID periodId, UUID employmentId, List<String> waive, String waiveReason) {
        Period p = require(AttendancePermissions.PERIOD_CLOSE, periods.forUpdate(periodId), periodId);
        if (!"OPEN".equals(p.status()) && !"IN_REVIEW".equals(p.status())) {
            throw ApiException.conflict("period-not-open", "A leaver is closed early only while the period is OPEN"
                    + " or IN_REVIEW (it is " + p.status() + ")");
        }
        LocalDate lwd = leaverEnd(p, employmentId);
        Set<String> waived = waivers(waive);
        periods.attachDays(p);
        checkClosable(checklist(p, employmentId, lwd, waived), waived, waiveReason);
        Optional<Result> live = results.live(p.id(), employmentId);
        if (live.isPresent() && results.activeHold(p.id(), employmentId).isPresent()) {
            throw ApiException.conflict("result-held", "Payroll holds the current early-close version of this"
                    + " employment; it must release it first (BR-ATT-087)");
        }
        Built days = lines.regular(p, employmentId, lwd, Set.of());
        if (days.lines().isEmpty()) {
            throw ApiException.unprocessable("no-days", "The employment has no computed day in the period up to "
                    + lwd + "; recalculate its timesheet first");
        }
        BigDecimal[] settlement = settlement(employmentId);
        Built built = withSettlement(days, settlement);
        List<Retro> retro = lines.retro(results.retroCandidates(p.legalEntityId(), p.payGroupId(), p.periodStart(),
                employmentId));
        int version = results.nextVersion(p.id(), employmentId);
        live.ifPresent(r -> results.withdraw(r.id(), "superseded by v" + version));
        UUID resultId = publish(p, employmentId, "EARLY_CLOSE", version, lwd, built, retro,
                live.map(Result::id).orElse(null), "EARLY_CLOSE");
        int retroLines = retro.stream().mapToInt(r -> r.lines().size()).sum();
        outbox.publish(new AttendancePeriodClosed(tenant(), p.legalEntityId(), p.id(), p.payGroupId(),
                p.periodStart(), p.periodEnd(), resultId, version, "EMPLOYMENT", employmentId, "EARLY_CLOSE",
                results.result(resultId).orElseThrow().checksum(), built.lines().size() + retroLines, retroLines));
        outbox.publish(new LeaverAttendanceClosed(tenant(), p.legalEntityId(), employmentId, p.id(),
                p.periodStart(), lwd, resultId, version, settlement[0], settlement[1]));
        retroEvents(p, resultId, version, retro);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("employmentId", employmentId.toString());
        details.put("version", String.valueOf(version));
        details.put("lastWorkingDate", lwd.toString());
        if (!waived.isEmpty()) {
            details.put("waived", String.join(",", waived));
            details.put("waiveReason", waiveReason);
        }
        audit.record(new BusinessAction("EARLY_CLOSE", "att", "attendance_period", p.id(), p.legalEntityId(),
                details));
        return results.result(resultId).orElseThrow();
    }

    /**
     * Reopen CLOSED → OPEN with a reason (BR-ATT-073). When the legal entity requires an approval
     * ({@code att.period_reopen_approval}) the reopen becomes a request in Core's workflow ({@code att.PERIOD_REOPEN})
     * and runs on the final approval. When Payroll holds the period the reopen is recorded, the payroll administrators
     * are notified and it runs once the hold is released (BR-ATT-087).
     */
    @AttTransactional
    public ReopenOutcome reopen(UUID id, String reason) {
        Period p = require(AttendancePermissions.PERIOD_REOPEN, periods.forUpdate(id), id);
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason-required", "Give the reason for reopening the period");
        }
        switch (p.status()) {
            case "LOCKED" -> throw ApiException.conflict("period-locked", "The period is LOCKED by an approved payroll"
                    + " run: it only reopens after a payroll unlock; later changes become retro deltas (BR-ATT-071)");
            case "CLOSED" -> {
            }
            default -> throw ApiException.conflict("period-not-closed", "Only a CLOSED period is reopened (it is "
                    + p.status() + ")");
        }
        if (approvals.required(ApprovalCatalogues.SETTING_REOPEN_APPROVAL, p.legalEntityId())) {
            return requestReopenApproval(p, reason.strip());
        }
        return reopenNow(p, reason.strip(), TenantContext.current().userAccountId());
    }

    /**
     * The reopen itself, after the permission and status checks (and the approval when one is required): files the
     * release request to Payroll when the period is held (BR-ATT-087), else reopens at once.
     */
    private ReopenOutcome reopenNow(Period p, String reason, UUID by) {
        UUID id = p.id();
        Optional<Hold> hold = results.activeHold(p.id(), null);
        if (hold.isPresent()) {
            periods.requestReopen(p.id(), reason, by);
            notifications.send(new SendRequest(PeriodNotificationTemplates.RELEASE_REQUESTED, "att",
                    new Recipients(null, List.of(Audience.permission(AttendancePermissions.PERIOD_HOLD,
                            new AuthorizationTarget.LegalEntity(p.legalEntityId())))),
                    Map.of("periodStart", VN_DATE.format(p.periodStart()), "periodEnd",
                            VN_DATE.format(p.periodEnd())),
                    "att.attendance_period", p.id(), "/attendance/periods/" + p.id(),
                    "att-release:" + hold.get().id(), null));
            audit.record(new BusinessAction("REOPEN_REQUEST", "att", "attendance_period", p.id(), p.legalEntityId(),
                    Map.of("reason", reason, "payrollRunId", hold.get().payrollRunId().toString())));
            return new ReopenOutcome("RELEASE_REQUESTED", view(periods.find(id).orElseThrow()), hold.get());
        }
        doReopen(p, reason, by, false);
        return new ReopenOutcome("REOPENED", view(periods.find(id).orElseThrow()), null);
    }

    /** ATT-P-11: the reopen waits for Core's approval; one open request per period (a repeat returns it). */
    private ReopenOutcome requestReopenApproval(Period p, String reason) {
        Optional<ChangeRequest> open = changeRequests.pendingReopen(p.id());
        if (open.isPresent()) {
            return new ReopenOutcome("APPROVAL_REQUESTED", view(p), results.activeHold(p.id(), null).orElse(null),
                    open.get().id());
        }
        UUID requestId = changeRequests.insertReopen(p.id(), p.legalEntityId(), reason);
        boolean held = results.activeHold(p.id(), null).isPresent();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("periodStart", p.periodStart().toString());
        summary.put("periodEnd", p.periodEnd().toString());
        summary.put("currentVersion", p.currentVersion());
        summary.put("heldByPayroll", held);
        summary.put("reason", reason);
        var started = workflow.start(new StartRequest(ApprovalCatalogues.PROCESS_PERIOD_REOPEN,
                ApprovalCatalogues.SUBJECT_CHANGE_REQUEST, requestId, 0, "att-period-reopen-" + requestId, null, null,
                p.legalEntityId(), null, null, null, summary, Map.of("heldByPayroll", held), Map.of()));
        changeRequests.setWorkflow(requestId, started.instanceId());
        audit.record(new BusinessAction("REOPEN_APPROVAL_REQUEST", "att", "attendance_period", p.id(),
                p.legalEntityId(), Map.of("reason", reason, "changeRequestId", requestId.toString())));
        if ("APPROVED".equals(started.status())) { // every step skipped
            applyReopenOutcome(requestId, "APPROVED", null);
            Period now = periods.find(p.id()).orElseThrow();
            ChangeRequest r = changeRequests.find(requestId).orElseThrow();
            String outcome = "REOPENED".equals(r.outcome()) || "RELEASE_REQUESTED".equals(r.outcome()) ? r.outcome()
                    : "APPROVAL_REQUESTED";
            return new ReopenOutcome(outcome, view(now), results.activeHold(p.id(), null).orElse(null), requestId);
        }
        return new ReopenOutcome("APPROVAL_REQUESTED", view(periods.find(p.id()).orElseThrow()),
                results.activeHold(p.id(), null).orElse(null), requestId);
    }

    /**
     * The final outcome of an {@code att.PERIOD_REOPEN} approval (workflow listener; idempotent). APPROVED runs the
     * reopen on behalf of the requester (no new permission check: the approval is the authority), unless the period
     * is no longer CLOSED (then NOT_APPLIED); a held period gets the release request of BR-ATT-087.
     */
    @AttTransactional
    public Map<String, Object> applyReopenOutcome(UUID requestId, String outcome, UUID decidedBy) {
        ChangeRequest r = changeRequests.find(requestId)
                .orElseThrow(() -> ApiException.notFound("Change request", requestId));
        if (!"PENDING".equals(r.status()) || !ChangeRequestStore.PERIOD_REOPEN.equals(r.requestType())) {
            return Map.of("status", r.status());
        }
        if (!"APPROVED".equals(outcome)) {
            String status = "REJECTED".equals(outcome) ? "REJECTED" : "CANCELLED";
            changeRequests.decide(r.id(), status, decidedBy, null, null);
            return Map.of("status", status);
        }
        Period p = periods.forUpdate(r.periodId()).orElseThrow();
        if (!"CLOSED".equals(p.status())) {
            String why = "period-" + p.status().toLowerCase(java.util.Locale.ROOT);
            changeRequests.decide(r.id(), "NOT_APPLIED", decidedBy, why, null);
            audit.record(new BusinessAction("REOPEN_NOT_APPLIED", "att", "attendance_period", p.id(),
                    p.legalEntityId(), Map.of("changeRequestId", r.id().toString(), "status", p.status())));
            return Map.of("status", "NOT_APPLIED", "reason", why);
        }
        ReopenOutcome o = reopenNow(p, r.reason(), r.createdBy());
        changeRequests.decide(r.id(), "APPLIED", decidedBy, o.outcome(), null);
        return Map.of("status", "APPLIED", "outcome", o.outcome());
    }

    /**
     * After Payroll released a PERIOD hold: runs a reopen requested while it was held (BR-ATT-087). Called inside
     * the release transaction; nothing happens without a pending request.
     */
    @AttTransactional
    public boolean reopenAfterRelease(UUID periodId) {
        Period p = periods.forUpdate(periodId).orElseThrow();
        if (!"CLOSED".equals(p.status()) || p.reopenRequestedAt() == null
                || results.activeHold(p.id(), null).isPresent()) {
            return false;
        }
        doReopen(p, p.reopenRequestReason(), p.reopenRequestedBy(), true);
        return true;
    }

    // ------------------------------------------------------------------------------------------------ internals

    /** [encash, recover] days of the leave settlement (BR-ATT-067), zero without the leave stream. */
    private BigDecimal[] settlement(UUID employmentId) {
        LeaveSettlements s = settlements.getIfAvailable();
        BigDecimal encash = BigDecimal.ZERO;
        BigDecimal recover = BigDecimal.ZERO;
        if (s != null) {
            for (LeaveSettlements.Line l : s.settlement(employmentId).lines()) {
                encash = encash.add(l.encashDays());
                recover = recover.add(l.recoverDays());
            }
        }
        return new BigDecimal[] {encash.setScale(2, RoundingMode.HALF_UP), recover.setScale(2, RoundingMode.HALF_UP)};
    }

    /** LEAVE_ENCASH_DAYS / LEAVE_RECOVER_DAYS (BRD §7.3 #15) on the last segment of the leaver's lines. */
    private static Built withSettlement(Built b, BigDecimal[] settlement) {
        NewLine last = b.lines().stream().max(java.util.Comparator.comparing(NewLine::segmentTo)).orElseThrow();
        List<NewLine> out = new ArrayList<>(b.lines());
        String[] cats = {"LEAVE_ENCASH_DAYS", "LEAVE_RECOVER_DAYS"};
        for (int i = 0; i < 2; i++) {
            if (settlement[i].signum() > 0) {
                out.add(new NewLine(last.employmentId(), last.assignmentKey(), last.segmentFrom(), last.segmentTo(),
                        last.legalEntityId(), last.orgUnitId(), last.payGroupId(), last.costCentreId(), cats[i], "DAY",
                        settlement[i], null, false, null, null, null, null, null));
            }
        }
        return new Built(out, b.overrideDays(), b.legalPackId(), b.employments());
    }

    private void doReopen(Period p, String reason, UUID by, boolean automatic) {
        results.live(p.id(), null).ifPresent(r -> results.withdraw(r.id(), "reopen: " + reason));
        periods.reopen(p.id(), reason, by);
        outbox.publish(new AttendancePeriodReopened(tenant(), p.legalEntityId(), p.id(), p.payGroupId(),
                p.periodStart(), p.periodEnd(), p.currentVersion(), reason, automatic));
        audit.record(new BusinessAction("REOPEN", "att", "attendance_period", p.id(), p.legalEntityId(),
                Map.of("reason", reason, "withdrawnVersion", String.valueOf(p.currentVersion()),
                        "automatic", String.valueOf(automatic))));
    }

    private UUID publish(Period p, UUID employmentId, String kind, int version, LocalDate to, Built built,
            List<Retro> retro, UUID supersedes, String cause) {
        List<NewLine> all = new ArrayList<>(built.lines());
        retro.forEach(r -> all.addAll(r.lines()));
        List<String> canonical = all.stream().map(l -> ResultLines.canonical(l.employmentId(), l.segmentFrom(),
                l.segmentTo(), l.category(), l.unit(), l.quantity(), l.periodQuantity(), l.precededByDayOt(),
                l.originalPeriodId(), l.originalSegmentFrom())).toList();
        String checksum = ResultLines.checksum(canonical);
        LocalDate otFrom = p.otPeriodStart();
        LocalDate otTo = p.otPeriodEnd();
        UUID resultId = results.insertResult(new NewResult(p.id(), p.periodStart(),
                employmentId == null ? "PERIOD" : "EMPLOYMENT", employmentId, kind, version, p.periodStart(), to,
                otFrom, otTo, checksum, built.legalPackId(), DayModel.RULE_VERSION + "/" + ResultLines.RULE_VERSION,
                built.overrideDays(), supersedes, cause));
        for (NewLine l : all) {
            results.insertLine(resultId, p.periodStart(), l);
        }
        for (Retro r : retro) {
            results.markRetro(r.requestIds(), r.lines().isEmpty() ? null : resultId);
        }
        return resultId;
    }

    private void retroEvents(Period target, UUID resultId, int version, List<Retro> retro) {
        for (Retro r : retro) {
            if (r.lines().isEmpty()) {
                continue;
            }
            outbox.publish(new RetroDeltaCreated(tenant(), target.legalEntityId(), r.request().employmentId(),
                    r.request().periodId(), r.request().periodStart(), r.originalResultId(), target.id(), resultId,
                    version, r.items()));
        }
    }

    private Checklist checklist(Period p, UUID employmentId, LocalDate to, Set<String> waived) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (p.stdDayMethod() == null) {
            counts.put("STD_METHOD_MISSING", 1L);
        }
        if (employmentId == null && !p.periodEnd().isBefore(clock.today())) {
            counts.put("PERIOD_NOT_ENDED", 1L);
        }
        if (employmentId == null) {
            long n = results.unprocessedEmployments(p.id(), p.legalEntityId(), p.periodStart(), to);
            if (n > 0) {
                counts.put("NOT_PROCESSED", n);
            }
        }
        for (Count c : results.blockingExceptions(p.id(), employmentId, p.periodStart(), to)) {
            if (DayModel.BLOCKING.contains(c.code()) || "QUARANTINED_PUNCH".equals(c.code())
                    || "OT_CAP".equals(c.code())) {
                String code = c.code().startsWith("MISSING_") ? "MISSING_PUNCH" : c.code();
                counts.merge(code, c.count(), Long::sum);
            }
        }
        long quarantined = results.quarantinedPunches(p.legalEntityId(), employmentId, p.periodStart(), to);
        if (quarantined > 0) {
            counts.merge("QUARANTINED_PUNCH", quarantined, Long::sum);
        }
        for (Count c : results.pendingRequests(p.legalEntityId(), employmentId, p.periodStart(), to)) {
            if (c.count() > 0) {
                counts.put(c.code(), c.count());
            }
        }
        List<ChecklistItem> items = new ArrayList<>();
        boolean canClose = true;
        for (var e : counts.entrySet()) {
            boolean waivable = !NOT_WAIVABLE.contains(e.getKey());
            items.add(new ChecklistItem(e.getKey(), e.getValue(), true, waivable));
            if (!(waivable && waived.contains(e.getKey()))) {
                canClose = false;
            }
        }
        return new Checklist(p.id(), employmentId, p.periodStart(), to, canClose, items);
    }

    private static void checkClosable(Checklist c, Set<String> waived, String reason) {
        if (!c.canClose()) {
            List<String> open = c.items().stream()
                    .filter(i -> !(i.waivable() && waived.contains(i.code()))).map(ChecklistItem::code).toList();
            throw ApiException.unprocessable("close-blocked", "Resolve or waive the blocking checklist items first: "
                    + String.join(", ", open), Map.of("items", c.items()));
        }
        boolean anyWaived = c.items().stream().anyMatch(i -> waived.contains(i.code()));
        if (anyWaived && (reason == null || reason.isBlank())) {
            throw ApiException.unprocessable("waive-reason-required", "Give the reason for waiving the checklist"
                    + " items (BR-ATT-074)");
        }
    }

    private static Set<String> waivers(List<String> waive) {
        Set<String> out = new LinkedHashSet<>();
        if (waive != null) {
            waive.stream().filter(w -> w != null && !w.isBlank()).map(w -> w.strip().toUpperCase(java.util.Locale.ROOT))
                    .forEach(out::add);
        }
        return out;
    }

    /** The leaver's last working day inside the period; 422 when the employment does not end in it. */
    private LocalDate leaverEnd(Period p, UUID employmentId) {
        PunchStore.EmploymentDates e = employments.employment(employmentId)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        LocalDate end = e.terminationDate();
        if (end == null || end.isBefore(p.periodStart()) || end.isAfter(p.periodEnd())) {
            throw ApiException.unprocessable("not-a-leaver-in-period", "The employment has no last working day"
                    + " between " + p.periodStart() + " and " + p.periodEnd() + " (BR-ATT-075 early close is for"
                    + " leavers)");
        }
        return end;
    }

    private Period require(String permission, Optional<Period> period, UUID id) {
        Period p = period.orElseThrow(() -> ApiException.notFound("Attendance period", id));
        if (!can(permission, p.legalEntityId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "period-not-in-scope",
                    "Attendance period " + id + " is outside your data scope");
        }
        return p;
    }

    private boolean can(String permission, UUID legalEntityId) {
        return authz.can(permission, new AuthorizationTarget.LegalEntity(legalEntityId));
    }

    private PeriodView view(Period p) {
        return new PeriodView(p.id(), p.legalEntityId(), p.payGroupId(), p.periodStart(), p.periodEnd(),
                p.otPeriodStart(), p.otPeriodEnd(), p.stdDayMethod(), p.stdDayValue(), p.closeDeadline(), p.status(),
                p.currentVersion(), p.lockedVersion(), p.closedAt(), p.reopenedAt(), p.reopenReason(),
                p.reopenRequestedAt(), p.reopenRequestReason(), results.activeHold(p.id(), null).orElse(null),
                p.version());
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
