package vn.yourhrm.attendance.internal.application.overtime;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.notify.OvertimeCapNotifier;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeDays.DayContext;
import vn.yourhrm.attendance.internal.application.overtime.OvertimePolicy.Limits;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Bucket;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Interval;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.CapStatus;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.Level;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.ProtectedOutcome;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.EmploymentFacts;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.LedgerRow;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.NewRequest;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.Placement;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.RequestRow;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.ResultView;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartResult;

/**
 * Pre-approved overtime (BRD P5, BR-ATT-040/044..046/048/049; detection BR-ATT-043 and TOIL BR-ATT-050 are P2).
 *
 * <p>Flow: a request is created for one or several employments (a manager's group request shares a
 * {@code request_group_id}), in DRAFT until the employee's consent is recorded (BLLĐ Art. 107(2)): the employee in ESS
 * ({@code att.overtime.consent}), or the manager recording signed paper or a verbal consent. Submitting runs the
 * checks; blocking findings keep the request in DRAFT. A clean submit reserves the minutes in the cap ledger
 * (pending) and starts Core's approval {@code att.OT_REQUEST}; the outcome moves them to approved or releases them.
 *
 * <p>Checks at submit: employed on the work date and scheduled; the work date is not in a closed attendance period;
 * no overlap with the planned shift or with another request; protected workers as of the work date (BR-ATT-048,
 * strictest rule of the legal pack, the type is never disclosed); the daily limit of normal + overtime hours
 * (BR-ATT-045, WARN or BLOCK per the legal entity's setting); the monthly and annual caps counting approved + pending
 * (+ opening YTD) minutes per legal entity (BR-ATT-046/047 P1), warning from the threshold and blocking above 100 %
 * unless an authorised override with a reason is given; and the 300-hour notice (BR-ATT-049).
 */
@Service
public class OvertimeService {

    public static final Set<String> CONSENT_METHODS = Set.of("ESS", "PAPER", "MANAGER_RECORDED");

    /** A check result; {@code severity} BLOCK keeps the request in DRAFT, WARN is shown to the requester. */
    public record Finding(String code, String severity, String message, Map<String, Object> data) {

        static Finding block(String code, String message) {
            return new Finding(code, "BLOCK", message, Map.of());
        }

        static Finding warn(String code, String message) {
            return new Finding(code, "WARN", message, Map.of());
        }

        boolean blocking() {
            return "BLOCK".equals(severity);
        }
    }

    public record CategoryMinutes(String category, int minutes, boolean precededByDayOt) {
    }

    public record RequestView(UUID id, UUID employmentId, UUID legalEntityId, UUID requestGroupId, LocalDate workDate,
            Instant plannedStartAt, Instant plannedEndAt, int plannedMinutes, String reason, String status,
            boolean employeeConsent, String consentMethod, Instant consentAt, boolean protectedWorker,
            boolean capOverride, String capOverrideReason, UUID workflowInstanceId, Instant decidedAt,
            List<CategoryMinutes> categories, int version) {
    }

    public record Outcome(RequestView request, boolean submitted, List<Finding> findings) {
    }

    public record CapView(String kind, LocalDate periodStart, String regime, int capMin, int openingMin,
            int approvedMin, int pendingMin, int paidMin, int overrideMin, int usedMin, String level) {
    }

    public record RegimeView(UUID legalEntityId, LocalDate asOf, String regimeRequested, String regimeApplied,
            String noticeRef, boolean noticeRequired, int monthCapMin, int yearCapMin) {
    }

    /**
     * @param consentMethod ESS (the employee's own action), PAPER (signed form kept by HR) or MANAGER_RECORDED
     */
    public record CreateCommand(List<UUID> employmentIds, LocalDate workDate, LocalTime startTime, LocalTime endTime,
            String reason, boolean consent, String consentMethod, boolean submit, String capOverrideReason) {
    }

    private final OvertimeStore store;
    private final OvertimeDays days;
    private final OvertimePolicy policy;
    private final OvertimeResults results;
    private final AuthorizationService authz;
    private final WorkflowService workflow;
    private final AuditLog audit;
    private final OvertimeCapNotifier capNotifier;

    public OvertimeService(OvertimeStore store, OvertimeDays days, OvertimePolicy policy, OvertimeResults results,
            AuthorizationService authz, WorkflowService workflow, AuditLog audit, OvertimeCapNotifier capNotifier) {
        this.capNotifier = capNotifier;
        this.store = store;
        this.days = days;
        this.policy = policy;
        this.results = results;
        this.authz = authz;
        this.workflow = workflow;
        this.audit = audit;
    }

    // ============================================================================================== commands

    @AttTransactional
    public List<Outcome> create(CreateCommand c) {
        if (c.employmentIds() == null || c.employmentIds().isEmpty() || c.workDate() == null
                || c.startTime() == null || c.endTime() == null) {
            throw ApiException.unprocessable("overtime-request-incomplete",
                    "Give the employment(s), the work date and the planned start and end times");
        }
        Set<UUID> employments = new LinkedHashSet<>(c.employmentIds());
        String method = c.consent() ? consentMethod(c.consentMethod()) : null;
        Map<UUID, Placement> placements = new LinkedHashMap<>();
        for (UUID e : employments) {
            Placement p = require(AttendancePermissions.OVERTIME_REQUEST, e, c.workDate());
            if ("ESS".equals(method) && !can(AttendancePermissions.OVERTIME_CONSENT, e, p, c.workDate())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "consent-not-allowed",
                        "Only the employee can give consent in ESS; record PAPER or MANAGER_RECORDED instead");
            }
            placements.put(e, p);
        }
        UUID group = employments.size() > 1 ? Ids.newId() : null;
        List<Outcome> out = new ArrayList<>();
        for (Map.Entry<UUID, Placement> en : placements.entrySet()) {
            Placement p = en.getValue();
            DayContext ctx = days.of(en.getKey(), c.workDate(), p.locationId());
            Interval local = interval(c.workDate(), c.startTime(), c.endTime(), ctx);
            Instant start = local.start().atZone(ctx.zone()).toInstant();
            Instant end = local.end().atZone(ctx.zone()).toInstant();
            int minutes = (int) Duration.between(start, end).toMinutes();
            UUID id = store.insertRequest(new NewRequest(en.getKey(), p.legalEntityId(), p.orgUnitId(), group,
                    c.workDate(), start, end, minutes, blankToNull(c.reason()), c.consent(), method));
            out.add(c.submit() ? doSubmit(id, c.capOverrideReason())
                    : new Outcome(view(id), false, check(store.request(id).orElseThrow(), null, false).findings()));
        }
        return out;
    }

    /** The employee's own consent in ESS (BLLĐ Art. 107(2)). */
    @AttTransactional
    public RequestView consent(UUID id) {
        RequestRow r = store.requestForUpdate(id).orElseThrow(() -> ApiException.notFound("Overtime request", id));
        require(AttendancePermissions.OVERTIME_CONSENT, r.employmentId(), r.workDate());
        if (!"DRAFT".equals(r.status())) {
            throw ApiException.conflict("overtime-request-not-draft", "Consent is given before the request is submitted");
        }
        store.recordConsent(id, "ESS");
        return view(id);
    }

    @AttTransactional
    public Outcome submit(UUID id, String capOverrideReason) {
        RequestRow r = store.request(id).orElseThrow(() -> ApiException.notFound("Overtime request", id));
        require(AttendancePermissions.OVERTIME_REQUEST, r.employmentId(), r.workDate());
        Outcome o = doSubmit(id, capOverrideReason);
        if (!o.submitted()) {
            Finding first = o.findings().stream().filter(Finding::blocking).findFirst().orElseThrow();
            throw ApiException.unprocessable(first.code(), first.message(), Map.of("findings", o.findings()));
        }
        return o;
    }

    /**
     * Cancels a DRAFT or APPROVED request (an APPROVED one needs a reason, and its work date must be in an open
     * period). A PENDING request is withdrawn through Core's workflow, which releases its minutes.
     */
    @AttTransactional
    public RequestView cancel(UUID id, String reason) {
        RequestRow r = store.requestForUpdate(id).orElseThrow(() -> ApiException.notFound("Overtime request", id));
        require(AttendancePermissions.OVERTIME_REQUEST, r.employmentId(), r.workDate());
        switch (r.status()) {
            case "DRAFT" -> store.decide(id, "CANCELLED", actor());
            case "APPROVED" -> {
                if (blankToNull(reason) == null) {
                    throw ApiException.unprocessable("reason-required", "Give the reason for cancelling approved overtime");
                }
                if (store.frozen(r.legalEntityId(), r.workDate())) {
                    throw ApiException.conflict("period-closed", "The work date is in a closed attendance period;"
                            + " reopen the period first");
                }
                store.decide(id, "CANCELLED", actor());
                ledger(r, 0, -r.plannedMinutes());
                results.recompute(r.employmentId(), r.workDate());
                audit.record(new BusinessAction("CANCEL", "att", "overtime_request", id, r.legalEntityId(),
                        Map.of("workDate", r.workDate().toString(), "reason", reason.strip())));
            }
            case "PENDING" -> throw ApiException.conflict("overtime-request-in-approval",
                    "The request is in approval; withdraw it in the workflow");
            default -> throw ApiException.conflict("overtime-request-final", "The request is already " + r.status());
        }
        return view(id);
    }

    /**
     * Applies the outcome of an {@code att.OT_REQUEST} approval (workflow listener). Idempotent. An approval is turned
     * into a rejection when the employee became a protected worker who may not work overtime since the submit.
     */
    @AttTransactional
    public Map<String, Object> applyOutcome(UUID id, String outcome, UUID decidedBy) {
        RequestRow r = store.requestForUpdate(id).orElse(null);
        if (r == null || !"PENDING".equals(r.status())) {
            return Map.of("overtimeStatus", r == null ? "GONE" : r.status());
        }
        if ("APPROVED".equals(outcome)) {
            Placement p = store.placement(r.employmentId(), r.workDate()).orElse(null);
            Limits limits = policy.limits(r.legalEntityId(), p == null ? null : p.locationId(), r.workDate());
            ProtectedOutcome po = OvertimeRules.protectedOutcome(store.protectedTypes(r.employmentId(), r.workDate()),
                    limits.protectedRules(), ownConsent(r));
            if (po == ProtectedOutcome.BLOCKED || po == ProtectedOutcome.NEEDS_OWN_CONSENT) {
                store.decide(id, "REJECTED", decidedBy);
                ledger(r, -r.plannedMinutes(), 0);
                return Map.of("overtimeStatus", "REJECTED", "reason", "protected-worker");
            }
            store.decide(id, "APPROVED", decidedBy);
            approve(r, limits);
            results.recompute(r.employmentId(), r.workDate());
            return Map.of("overtimeStatus", "APPROVED");
        }
        String status = "REJECTED".equals(outcome) ? "REJECTED" : "CANCELLED";
        store.decide(id, status, decidedBy);
        ledger(r, -r.plannedMinutes(), 0);
        return Map.of("overtimeStatus", status);
    }

    // ================================================================================================= reads

    @AttTransactional(readOnly = true)
    public RequestView get(UUID id) {
        RequestRow r = store.request(id).orElseThrow(() -> ApiException.notFound("Overtime request", id));
        require(AttendancePermissions.OVERTIME_VIEW, r.employmentId(), r.workDate());
        return view(id);
    }

    @AttTransactional(readOnly = true)
    public List<RequestView> list(UUID employmentId, LocalDate from, LocalDate to) {
        range(from, to);
        require(AttendancePermissions.OVERTIME_VIEW, employmentId, to);
        return store.requestsOfEmployment(employmentId, from, to).stream().map(r -> view(r, null)).toList();
    }

    @AttTransactional(readOnly = true)
    public List<ResultView> results(UUID employmentId, LocalDate from, LocalDate to) {
        range(from, to);
        require(AttendancePermissions.OVERTIME_VIEW, employmentId, to);
        return store.results(employmentId, from, to);
    }

    /** Month and year cap usage of the employment on {@code date} (BR-ATT-046). */
    @AttTransactional(readOnly = true)
    public List<CapView> caps(UUID employmentId, LocalDate date) {
        Placement p = require(AttendancePermissions.OVERTIME_VIEW, employmentId, date);
        return capsOf(employmentId, p, date);
    }

    /** {@link #caps} without the permission check (ESS: the caller's own employment, BR-ATT-080). */
    @AttTransactional(readOnly = true)
    public List<CapView> capsOf(UUID employmentId, LocalDate date) {
        Placement p = store.placement(employmentId, date)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        return capsOf(employmentId, p, date);
    }

    private List<CapView> capsOf(UUID employmentId, Placement p, LocalDate date) {
        Limits limits = policy.limits(p.legalEntityId(), p.locationId(), date);
        LocalDate month = date.withDayOfMonth(1);
        LocalDate year = date.withDayOfYear(1);
        Map<String, LedgerRow> rows = new LinkedHashMap<>();
        store.ledgerOfEmployment(employmentId, month, year).forEach(l -> rows.put(l.periodKind(), l));
        return List.of(capView("MONTH", month, limits.monthCapMin(), limits, rows.get("MONTH")),
                capView("YEAR", year, limits.yearCapMin(), limits, rows.get("YEAR")));
    }

    /** The legal entity's annual regime and whether the 300-hour notice is still to be recorded (BR-ATT-049). */
    @AttTransactional(readOnly = true)
    public RegimeView regime(UUID legalEntityId, LocalDate date) {
        if (!authz.can(AttendancePermissions.OVERTIME_VIEW, new AuthorizationTarget.LegalEntity(legalEntityId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "legal-entity-not-in-scope",
                    "Legal entity " + legalEntityId + " is outside your data scope");
        }
        Limits l = policy.limits(legalEntityId, null, date);
        return new RegimeView(legalEntityId, date, l.regimeRequested(), l.regime(), l.noticeRef(), l.noticeMissing(),
                l.monthCapMin(), l.yearCapMin());
    }

    // ============================================================================================== submit

    private record Checked(List<Finding> findings, Limits limits, LedgerRow month, LedgerRow year,
            boolean override, boolean protectedWorker) {
    }

    private Outcome doSubmit(UUID id, String capOverrideReason) {
        RequestRow r = store.requestForUpdate(id).orElseThrow(() -> ApiException.notFound("Overtime request", id));
        if (!"DRAFT".equals(r.status())) {
            throw ApiException.conflict("overtime-request-not-draft", "Only a DRAFT request can be submitted");
        }
        Checked c = check(r, blankToNull(capOverrideReason), true);
        if (c.findings().stream().anyMatch(Finding::blocking)) {
            return new Outcome(view(id), false, c.findings());
        }
        store.markPending(id, c.protectedWorker(), c.override(), blankToNull(capOverrideReason));
        store.adjustLedger(c.month().id(), r.plannedMinutes(), 0, 0);
        store.adjustLedger(c.year().id(), r.plannedMinutes(), 0, 0);
        if (c.override()) {
            audit.record(new BusinessAction("OVERRIDE", "att", "overtime_request", id, r.legalEntityId(),
                    Map.of("workDate", r.workDate().toString(), "minutes", String.valueOf(r.plannedMinutes()),
                            "reason", capOverrideReason.strip())));
        }
        StartResult started = workflow.start(new StartRequest(OvertimeCatalogues.PROCESS_OT_REQUEST,
                OvertimeCatalogues.SUBJECT_TYPE, id, r.version() + 1, "ot-request:" + id, null,
                store.employment(r.employmentId()).map(EmploymentFacts::personId).orElse(null), r.legalEntityId(),
                r.orgUnitId(), store.placement(r.employmentId(), r.workDate()).map(Placement::locationId).orElse(null),
                null, summary(r, c), Map.of("plannedMinutes", r.plannedMinutes(), "capOverride", c.override(),
                        "protectedWorker", c.protectedWorker()), Map.of()));
        store.setWorkflowInstance(id, started.instanceId());
        notifyCapThresholds(r, c);
        return new Outcome(view(id), true, c.findings());
    }

    /**
     * Runs every check. With {@code lock}, the month and year ledger rows are created and locked (serialising cap
     * checks of the employee); without it (DRAFT preview) they are only read.
     */
    private Checked check(RequestRow r, String capOverrideReason, boolean lock) {
        List<Finding> f = new ArrayList<>();
        if (!r.employeeConsent()) {
            f.add(Finding.block("consent-required", "The employee's consent to overtime is required (BLLĐ Art. 107)"));
        }
        EmploymentFacts emp = store.employment(r.employmentId()).orElse(null);
        if (emp == null || !emp.employedOn(r.workDate())) {
            f.add(Finding.block("not-employed-on-date", "The employee is not employed on " + r.workDate()));
        }
        if (store.frozen(r.legalEntityId(), r.workDate())) {
            f.add(Finding.block("period-closed", "The work date is in a closed attendance period"));
        }
        Placement p = store.placement(r.employmentId(), r.workDate()).orElse(null);
        Limits limits = policy.limits(r.legalEntityId(), p == null ? null : p.locationId(), r.workDate());
        DayContext ctx = days.of(r.employmentId(), r.workDate(), p == null ? null : p.locationId());
        if (!ctx.scheduled()) {
            f.add(Finding.block("employment-not-scheduled", "The employee has no work schedule on " + r.workDate()));
        }
        Interval local = ctx.local(r.plannedStartAt(), r.plannedEndAt());
        if (ctx.shiftStart() != null && local.start().isBefore(ctx.shiftEnd())
                && local.end().isAfter(ctx.shiftStart())) {
            f.add(Finding.block("ot-overlaps-shift", "Overtime cannot overlap the planned shift ("
                    + ctx.shiftStart().toLocalTime() + "-" + ctx.shiftEnd().toLocalTime() + ")"));
        }
        if (!store.overlapping(r.employmentId(), r.plannedStartAt(), r.plannedEndAt(), r.id()).isEmpty()) {
            f.add(Finding.block("ot-request-overlap", "Another overtime request already covers part of this time"));
        }

        // protected workers as of the work date (BR-ATT-048): the type is never disclosed
        ProtectedOutcome po = OvertimeRules.protectedOutcome(store.protectedTypes(r.employmentId(), r.workDate()),
                limits.protectedRules(), ownConsent(r));
        if (po == ProtectedOutcome.BLOCKED) {
            f.add(Finding.block("protected-worker", "The employee is a protected worker on this date:"
                    + " overtime is not allowed"));
        } else if (po == ProtectedOutcome.NEEDS_OWN_CONSENT) {
            f.add(Finding.block("protected-worker-own-consent-required", "The employee is a protected worker on"
                    + " this date: overtime needs the employee's own consent (ESS or signed paper)"));
        }

        // daily limit (BR-ATT-045)
        int otOfDay = store.requestsOfDay(r.employmentId(), r.workDate(), Set.of("PENDING", "APPROVED")).stream()
                .filter(x -> !x.id().equals(r.id())).mapToInt(RequestRow::plannedMinutes).sum();
        CapStatus day = OvertimeRules.daily(ctx.workingDay(), ctx.normalMin(), otOfDay, r.plannedMinutes(),
                limits.maxDayTotalMin(), limits.maxDayOffMin());
        if (day.level() == Level.EXCEEDED) {
            Finding d = new Finding("ot-cap-day-exceeded", limits.blockDaily() ? "BLOCK" : "WARN",
                    (ctx.workingDay() ? "Normal + overtime hours exceed " : "Overtime hours exceed ")
                            + hours(day.capMin()) + " h on " + r.workDate(), capData(day));
            f.add(d);
        }

        // monthly and annual caps per legal entity (BR-ATT-046, 047 P1), 300-hour notice (BR-ATT-049)
        if (limits.noticeMissing()) {
            f.add(Finding.warn("ot-300h-notice-missing", "The legal entity is set to the 300-hour regime but the"
                    + " notice to the provincial labour authority is not recorded: the 200-hour cap applies"));
        }
        LocalDate month = r.workDate().withDayOfMonth(1);
        LocalDate year = r.workDate().withDayOfYear(1);
        LedgerRow m;
        LedgerRow y;
        if (lock) {
            m = store.lockLedger(r.employmentId(), r.legalEntityId(), emp == null ? null : emp.personId(), "MONTH",
                    month, limits.regime(), limits.monthCapMin(), limits.packId(), null);
            y = store.lockLedger(r.employmentId(), r.legalEntityId(), emp == null ? null : emp.personId(), "YEAR",
                    year, limits.regime(), limits.yearCapMin(), limits.packId(), limits.noticeRef());
        } else {
            Map<String, LedgerRow> rows = new LinkedHashMap<>();
            store.ledgerOfEmployment(r.employmentId(), month, year).forEach(l -> rows.put(l.periodKind(), l));
            m = rows.get("MONTH");
            y = rows.get("YEAR");
        }
        boolean exceeded = false;
        for (CapStatus s : List.of(
                OvertimeRules.cap("MONTH", limits.monthCapMin(), m == null ? 0 : m.usedMin(), r.plannedMinutes(),
                        limits.warnPercent()),
                OvertimeRules.cap("YEAR", limits.yearCapMin(), y == null ? 0 : y.usedMin(), r.plannedMinutes(),
                        limits.warnPercent()))) {
            String k = s.kind().toLowerCase(Locale.ROOT);
            if (s.level() == Level.WARN) {
                f.add(new Finding("ot-cap-" + k + "-warning", "WARN", "Overtime reaches " + percent(s)
                        + " % of the " + k + "ly cap of " + hours(s.capMin()) + " h", capData(s)));
            } else if (s.level() == Level.EXCEEDED) {
                exceeded = true;
                f.add(new Finding("ot-cap-" + k + "-exceeded", "BLOCK", "Overtime exceeds the " + k + "ly cap of "
                        + hours(s.capMin()) + " h by " + s.excessMin() + " min", capData(s)));
            }
        }
        boolean override = false;
        if (exceeded && capOverrideReason != null) {
            if (p != null && can(AttendancePermissions.OVERTIME_CAP_OVERRIDE, r.employmentId(), p, r.workDate())) {
                override = true;
                f.replaceAll(x -> x.code().endsWith("-exceeded") && x.code().startsWith("ot-cap-")
                        && !x.code().equals("ot-cap-day-exceeded")
                        ? new Finding(x.code(), "WARN", x.message() + " (authorised override)", x.data()) : x);
            } else {
                f.add(Finding.block("cap-override-not-allowed", "You may not override the overtime cap"));
            }
        }
        return new Checked(f, limits, m, y, override, po != ProtectedOutcome.NOT_PROTECTED);
    }

    /**
     * The submit reached a monthly / annual warning threshold (or an authorised override went above the cap): the
     * employee and the cap-override holders are told (BR-ATT-082).
     */
    private void notifyCapThresholds(RequestRow r, Checked c) {
        Placement p = store.placement(r.employmentId(), r.workDate()).orElse(null);
        for (Finding f : c.findings()) {
            Object kind = f.data().get("kind");
            if (!f.code().startsWith("ot-cap-") || f.code().startsWith("ot-cap-day") || kind == null) {
                continue;
            }
            int cap = ((Number) f.data().get("capMin")).intValue();
            int after = ((Number) f.data().get("afterMin")).intValue();
            capNotifier.thresholdReached(r.employmentId(), r.legalEntityId(), r.orgUnitId(),
                    p == null ? null : p.locationId(), r.workDate(), kind.toString(),
                    cap <= 0 ? 0 : Math.round(after * 100.0 / cap));
        }
    }

    /** Moves the minutes from pending to approved; an override books the minutes above the cap. */
    private void approve(RequestRow r, Limits limits) {
        EmploymentFacts emp = store.employment(r.employmentId()).orElse(null);
        UUID person = emp == null ? null : emp.personId();
        LedgerRow m = store.lockLedger(r.employmentId(), r.legalEntityId(), person, "MONTH",
                r.workDate().withDayOfMonth(1), limits.regime(), limits.monthCapMin(), limits.packId(), null);
        LedgerRow y = store.lockLedger(r.employmentId(), r.legalEntityId(), person, "YEAR",
                r.workDate().withDayOfYear(1), limits.regime(), limits.yearCapMin(), limits.packId(),
                limits.noticeRef());
        for (LedgerRow l : List.of(m, y)) {
            // the usage the submit check saw: opening + approved + pending (this request still pending)
            int after = l.usedMin();
            int excess = r.capOverride() ? Math.max(0, Math.min(r.plannedMinutes(), after - l.capMin())) : 0;
            store.adjustLedger(l.id(), -r.plannedMinutes(), r.plannedMinutes(), excess);
        }
    }

    private void ledger(RequestRow r, int pendingDelta, int approvedDelta) {
        for (LedgerRow l : store.ledgerOfEmployment(r.employmentId(), r.workDate().withDayOfMonth(1),
                r.workDate().withDayOfYear(1))) {
            store.adjustLedger(l.id(), pendingDelta, approvedDelta, 0);
        }
    }

    // ============================================================================================= helpers

    /**
     * The planned interval: {@code startTime} on the work date (on the next day when the shift crosses midnight and
     * the overtime starts before the shift's start, i.e. after a night shift), ending at {@code endTime} on that day or
     * the next one.
     */
    static Interval interval(LocalDate workDate, LocalTime startTime, LocalTime endTime, DayContext ctx) {
        LocalDate startDay = ctx.crossesMidnight() && startTime.isBefore(ctx.shiftStart().toLocalTime())
                ? workDate.plusDays(1) : workDate;
        LocalDateTime start = startDay.atTime(startTime);
        LocalDateTime end = (endTime.isAfter(startTime) ? startDay : startDay.plusDays(1)).atTime(endTime);
        return new Interval(start, end);
    }

    private static boolean ownConsent(RequestRow r) {
        return r.employeeConsent() && ("ESS".equals(r.consentMethod()) || "PAPER".equals(r.consentMethod()));
    }

    private Placement require(String permission, UUID employmentId, LocalDate date) {
        Placement p = store.placement(employmentId, date)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (!can(permission, employmentId, p, date)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "employment-not-in-scope",
                    "Employment " + employmentId + " is outside your data scope");
        }
        return p;
    }

    private boolean can(String permission, UUID employmentId, Placement p, LocalDate date) {
        return authz.can(permission, new AuthorizationTarget.Employee(employmentId, p.legalEntityId(), p.orgUnitId(),
                p.locationId()), date);
    }

    private RequestView view(UUID id) {
        RequestRow r = store.request(id).orElseThrow(() -> ApiException.notFound("Overtime request", id));
        Placement p = store.placement(r.employmentId(), r.workDate()).orElse(null);
        List<CategoryMinutes> cats = null;
        try {
            Limits limits = policy.limits(r.legalEntityId(), p == null ? null : p.locationId(), r.workDate());
            DayContext ctx = days.of(r.employmentId(), r.workDate(), p == null ? null : p.locationId());
            cats = OvertimeResults.classify(List.of(ctx.local(r.plannedStartAt(), r.plannedEndAt())), ctx, limits)
                    .stream().map(OvertimeService::minutes).toList();
        } catch (ApiException e) {
            if (!"legal-parameter-missing".equals(e.code())) {
                throw e;
            }
        }
        return view(r, cats);
    }

    private static RequestView view(RequestRow r, List<CategoryMinutes> categories) {
        return new RequestView(r.id(), r.employmentId(), r.legalEntityId(), r.requestGroupId(), r.workDate(),
                r.plannedStartAt(), r.plannedEndAt(), r.plannedMinutes(), r.reason(), r.status(),
                r.employeeConsent(), r.consentMethod(), r.consentAt(), r.protectedWorkerFlag(), r.capOverride(),
                r.capOverrideReason(), r.workflowInstanceId(), r.decidedAt(), categories, r.version());
    }

    private static CategoryMinutes minutes(Bucket b) {
        return new CategoryMinutes(b.category().code(), b.minutes(), b.precededByDayOt());
    }

    private static CapView capView(String kind, LocalDate start, int cap, Limits limits, LedgerRow l) {
        int used = l == null ? 0 : l.usedMin();
        Level level = OvertimeRules.cap(kind, cap, used, 0, limits.warnPercent()).level();
        return l == null
                ? new CapView(kind, start, limits.regime(), cap, 0, 0, 0, 0, 0, 0, level.name())
                : new CapView(kind, start, l.regimeCode(), cap, l.openingMin(), l.approvedMin(), l.pendingMin(),
                        l.paidMin(), l.overrideMin(), used, level.name());
    }

    private static Map<String, Object> summary(RequestRow r, Checked c) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("workDate", r.workDate().toString());
        s.put("start", r.plannedStartAt().toString());
        s.put("end", r.plannedEndAt().toString());
        s.put("plannedMinutes", r.plannedMinutes());
        if (r.reason() != null) {
            s.put("reason", r.reason());
        }
        s.put("capOverride", c.override());
        s.put("protectedWorker", c.protectedWorker());
        return s;
    }

    private static Map<String, Object> capData(CapStatus s) {
        return Map.of("kind", s.kind(), "capMin", s.capMin(), "usedMin", s.usedMin(), "requestMin", s.requestMin(),
                "afterMin", s.afterMin());
    }

    private static String hours(int minutes) {
        return minutes % 60 == 0 ? String.valueOf(minutes / 60) : String.format(Locale.ROOT, "%.2f", minutes / 60.0);
    }

    private static long percent(CapStatus s) {
        return Math.round(s.afterMin() * 100.0 / s.capMin());
    }

    private static String consentMethod(String m) {
        String v = m == null ? null : m.strip().toUpperCase(Locale.ROOT);
        if (v == null || !CONSENT_METHODS.contains(v)) {
            throw ApiException.unprocessable("invalid-consent-method",
                    "The consent method is one of " + CONSENT_METHODS);
        }
        return v;
    }

    private static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || from.plusDays(366).isBefore(to)) {
            throw ApiException.unprocessable("invalid-range", "Give from <= to, at most 366 days");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }
}
