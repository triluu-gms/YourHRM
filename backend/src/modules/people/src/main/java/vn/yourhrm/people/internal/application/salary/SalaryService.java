package vn.yourhrm.people.internal.application.salary;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.SalaryEvents.Allowance;
import vn.yourhrm.people.api.SalaryEvents.SalaryChanged;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.HrMasking;
import vn.yourhrm.people.internal.application.assignment.EmploymentFacts;
import vn.yourhrm.people.internal.application.assignment.EmploymentFacts.Employment;
import vn.yourhrm.people.internal.application.assignment.RetroFlags;
import vn.yourhrm.people.internal.application.assignment.RetroFlags.Retro;
import vn.yourhrm.people.internal.application.assignment.Warning;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrJson;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.assignment.ProcessedMarkers;
import vn.yourhrm.people.internal.infra.salary.SalaryRow;
import vn.yourhrm.people.internal.infra.salary.SalaryStore;
import vn.yourhrm.shared.auth.api.MaskLevel;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartResult;

/**
 * Agreed compensation (EP-PPL-05; BR-PPL-040/041/044, NFR-PPL-001/006). People is the system of record of the salary
 * record (GROSS / NET, agreed net, net-guaranteed scope, SI salary, allowances by Payroll component code, probation %),
 * bi-temporal like the assignment. There is no direct edit: a salary is created or changed only by an approved
 * decision or contract appendix through process type {@code hr.SALARY_CHANGE} (US-PPL-042); the other writers are the
 * internal {@link #createInitial} used by hire and the opening-balance import. Viewing needs
 * {@code hr.compensation.view}, changing {@code hr.compensation.update} (US-PPL-044); amounts are read only through
 * {@code hr.v_salary_record_masked} (fail closed), and every list / detail view and every change is written to Core's
 * audit with its purpose (US-PPL-045).
 */
@Service
public class SalaryService {

    static final String PROPOSAL_MARKER = "hr.salary.proposal";
    static final String DUE_MARKER = "hr.salary.due";
    static final Set<String> CHANGE_ACTIONS = Set.of("INCREASE", "DECREASE", "PROMOTION", "ADJUSTMENT");

    /** The pay terms of a version. {@code allowances} by Payroll component code; amounts VND. */
    public record SalaryTerms(String payBasis, String salaryKind, BigDecimal baseSalaryAmount,
            BigDecimal agreedNetAmount, List<String> netGuaranteedScope, BigDecimal siSalaryAmount,
            String currencyCode, String payScaleCode, String payScaleStepCode, BigDecimal probationPct,
            List<Allowance> allowances) {
    }

    /**
     * A salary-change request (US-PPL-042). With {@code correctsRecordId} it corrects that version (same validity,
     * {@code correctionReason} required, US-PPL-037). {@code contractAppendixId}: the change is issued by that appendix
     * instead of a decision. {@code bandOverrideReason}: the proposer's justification when outside the pay band.
     */
    public record ChangeRequest(String actionCode, String reasonCode, LocalDate effectiveDate, SalaryTerms terms,
            UUID contractAppendixId, UUID correctsRecordId, String correctionReason, String bandOverrideReason,
            String signatoryName, String signatoryTitle) {
    }

    /** {@code status}: PENDING_APPROVAL, APPLIED or SCHEDULED. No amounts. */
    public record ChangeResult(UUID requestId, String status, UUID workflowInstanceId, UUID salaryRecordId,
            UUID decisionId, String decisionNo, List<Warning> warnings) {
    }

    /** A version as shown to a holder of {@code hr.compensation.view}; amounts null when masked. */
    public record SalaryView(UUID id, UUID employmentId, String payBasis, String salaryKind,
            BigDecimal baseSalaryAmount, BigDecimal agreedNetAmount, List<String> netGuaranteedScope,
            BigDecimal siSalaryAmount, String currencyCode, String payScaleCode, String payScaleStepCode,
            BigDecimal probationPct, List<Allowance> allowances, String actionCode, String reasonCode,
            LocalDate validFrom, LocalDate validTo, Instant knownFrom, Instant knownTo, UUID supersededBy,
            String correctionReason, String source, UUID decisionId, String decisionNo, UUID decisionDocumentId,
            UUID contractAppendixId, String appendixNo, UUID appendixDocumentId, boolean amountsMasked) {
    }

    /** One line of the salary list (current version per employment in scope). */
    public record SalaryListItem(UUID employmentId, String employeeNo, String fullName, UUID legalEntityId,
            SalaryView salary) {
    }

    /** What applying a proposal produced. */
    public record Applied(SalaryRow row, UUID decisionId, String decisionNo, boolean inForce) {
    }

    private final HrDb db;
    private final HrJson json;
    private final SalaryStore store;
    private final SalaryRules rules;
    private final ProcessedMarkers markers;
    private final EmploymentFacts employments;
    private final EmployeeAccess access;
    private final HrMasking masking;
    private final CompensationPurposes purposes;
    private final DecisionService decisions;
    private final WorkflowService workflow;
    private final HrOutbox outbox;
    private final RetroFlags retro;
    private final AuditLog audit;
    private final BusinessClock clock;

    public SalaryService(HrDb db, HrJson json, SalaryStore store, SalaryRules rules, ProcessedMarkers markers,
            EmploymentFacts employments, EmployeeAccess access, HrMasking masking, CompensationPurposes purposes,
            DecisionService decisions, WorkflowService workflow, HrOutbox outbox, RetroFlags retro, AuditLog audit,
            BusinessClock clock) {
        this.db = db;
        this.json = json;
        this.store = store;
        this.rules = rules;
        this.markers = markers;
        this.employments = employments;
        this.access = access;
        this.masking = masking;
        this.purposes = purposes;
        this.decisions = decisions;
        this.workflow = workflow;
        this.outbox = outbox;
        this.retro = retro;
        this.audit = audit;
        this.clock = clock;
    }

    /** The id of the version created by proposal {@code proposalId} (and back). */
    public static UUID appliedIdOf(UUID proposalId) {
        return new UUID(proposalId.getMostSignificantBits(), proposalId.getLeastSignificantBits() ^ 0x0000_5EED_0000_0002L);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Internal API for the hire and opening-balance streams

    /**
     * The first salary record of an employment (hire with its decision or appendix, or the opening-balance import with
     * {@code source = OPENING_LOAD}, the only writer without a decision / appendix). Runs in the caller's People
     * transaction, no permission check. APP / REC_HANDOFF records are checked against the minimum wage (block) and the
     * band (warnings returned); imports are checked for structure only (historic data). Publishes
     * {@code people.SalaryChanged} now when {@code validFrom} has come ({@code initialLoad} for OPENING_LOAD), otherwise
     * the daily job publishes it on that date.
     */
    @HrTransactional
    public Created createInitial(UUID employmentId, SalaryTerms terms, LocalDate validFrom, UUID decisionId,
            UUID contractAppendixId, String source) {
        String src = source == null ? "APP" : source;
        boolean imported = "OPENING_LOAD".equals(src) || "IMPORT".equals(src);
        if (!imported && decisionId == null && contractAppendixId == null) {
            throw ApiException.unprocessable("decision-or-appendix-required",
                    "A salary record is created only by a decision or a contract appendix (BR-PPL-040)");
        }
        Employment emp = employments.require(employmentId);
        if (!store.line(employmentId).isEmpty()) {
            throw ApiException.conflict("salary-record-exists", "Employment " + emp.employeeNo()
                    + " already has a salary record; use a salary change");
        }
        SalaryTerms t = rules.structure(terms, validFrom);
        List<Warning> warnings = imported ? List.of() : rules.business(emp, t, validFrom);
        SalaryRow row = newRow(Ids.newId(), emp.id(), t, "HIRE", null, decisionId, contractAppendixId, validFrom, null,
                src, null);
        store.insert(row);
        audit.record(new BusinessAction("CREATE_SALARY_RECORD", "hr", "salary_record", row.id(), emp.legalEntityId(),
                details("employmentId", emp.id(), "source", src, "purpose", "HIRE")));
        if (!validFrom.isAfter(clock.today())) {
            publish(emp, row, "HIRE", null, "OPENING_LOAD".equals(src));
        }
        return new Created(row.id(), warnings);
    }

    public record Created(UUID salaryRecordId, List<Warning> warnings) {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Salary change through approval (US-PPL-042/043)

    @HrTransactional
    public ChangeResult requestChange(UUID employmentId, ChangeRequest req) {
        access.require(PeoplePermissions.COMPENSATION_UPDATE, employmentId,
                req.effectiveDate() == null ? clock.today() : req.effectiveDate());
        Employment emp = employments.require(employmentId);
        if (!emp.live()) {
            throw ApiException.unprocessable("employment-not-live", "Employment " + emp.employeeNo() + " is "
                    + emp.status());
        }
        List<SalaryRow> line = store.line(employmentId);
        LocalDate d;
        String action;
        SalaryRow corrected = null;
        if (req.correctsRecordId() != null) {
            corrected = line.stream().filter(r -> r.id().equals(req.correctsRecordId())).findFirst().orElseThrow(() ->
                    ApiException.unprocessable("salary-record-not-current", "Salary record " + req.correctsRecordId()
                            + " is not a current version of this employment"));
            if (req.correctionReason() == null || req.correctionReason().isBlank()
                    || req.correctionReason().startsWith("PROPOSAL")) {
                throw ApiException.unprocessable("reason-required", "A correction needs a correction reason");
            }
            d = corrected.validFrom();
            action = "CORRECTION";
        } else {
            if (req.effectiveDate() == null) {
                throw ApiException.unprocessable("effective-date-required", "effectiveDate is required");
            }
            d = req.effectiveDate();
            action = req.actionCode() == null ? "ADJUSTMENT" : req.actionCode();
            if (!CHANGE_ACTIONS.contains(action)) {
                throw ApiException.unprocessable("invalid-action", "actionCode must be one of " + CHANGE_ACTIONS);
            }
            if (!line.isEmpty()) {
                SalaryRow v = line.stream().filter(r -> r.covers(d)).findFirst().orElseThrow(() ->
                        ApiException.unprocessable("no-salary-at-date", "Employment " + emp.employeeNo()
                                + " has no salary record on " + d));
                if (v.validFrom().equals(d)) {
                    throw ApiException.unprocessable("salary-version-exists", "A salary version already starts on "
                            + d + "; request a correction of it instead");
                }
            }
        }
        if (!emp.covers(d)) {
            throw ApiException.unprocessable("outside-employment", "The effective date is outside the employment");
        }
        if (req.contractAppendixId() != null) {
            requireAppendix(employmentId, req.contractAppendixId());
        }
        SalaryTerms t = rules.structure(req.terms(), d);
        List<Warning> warnings = rules.business(emp, t, d);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", corrected == null ? "CHANGE" : "CORRECTION");
        put(payload, "correctsRecordId", req.correctsRecordId());
        put(payload, "correctionReason", req.correctionReason());
        put(payload, "bandOverrideReason", req.bandOverrideReason());
        put(payload, "signatoryName", req.signatoryName());
        put(payload, "signatoryTitle", req.signatoryTitle());
        payload.put("warnings", warnings.stream().map(Warning::code).toList());
        UUID proposalId = Ids.newId();
        SalaryRow proposal = newRow(proposalId, emp.id(), t, action, req.reasonCode(), null, req.contractAppendixId(),
                d, corrected == null ? null : corrected.validTo(), "APP", null);
        store.insertProposal(proposal, payload);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("employeeNo", emp.employeeNo());
        summary.put("employeeName", emp.fullName());
        summary.put("actionCode", action);
        summary.put("effectiveDate", d.toString());
        summary.put("correction", corrected != null);
        summary.put("warnings", warnings.stream().map(Warning::code).toList());
        UUID orgUnit = currentOrgUnit(emp.id(), d);
        StartResult started = workflow.start(new StartRequest(SalaryCatalogues.SALARY_CHANGE, "hr.salary_record",
                proposalId, null, "hr.salary_record:" + proposalId, null, emp.personId(), emp.legalEntityId(),
                orgUnit, null, null, summary, Map.of("actionCode", action, "correction", corrected != null), Map.of()));
        audit.record(new BusinessAction("PROPOSE_SALARY_CHANGE", "hr", "salary_record", proposalId,
                emp.legalEntityId(), details("employmentId", emp.id(), "actionCode", action, "effectiveDate", d,
                        "purpose", corrected == null ? "SALARY_CHANGE" : "SALARY_CORRECTION",
                        "correctionReason", req.correctionReason(), "bandOverrideReason", req.bandOverrideReason(),
                        "workflowInstanceId", started.instanceId())));
        if ("APPROVED".equals(started.status())) {
            Optional<Applied> applied = resolveProposal(proposalId, started.instanceId(), "APPROVED");
            if (applied.isPresent()) {
                Applied a = applied.get();
                return new ChangeResult(proposalId, a.inForce() ? "APPLIED" : "SCHEDULED", started.instanceId(),
                        a.row().id(), a.decisionId(), a.decisionNo(), warnings);
            }
        }
        return new ChangeResult(proposalId, "PENDING_APPROVAL", started.instanceId(), null, null, null, warnings);
    }

    /** Applies (APPROVED) or closes a salary proposal after its workflow; idempotent per proposal. */
    @HrTransactional
    public Optional<Applied> resolveProposal(UUID proposalId, UUID workflowInstanceId, String outcome) {
        markers.lock(PROPOSAL_MARKER, proposalId);
        if (markers.exists(PROPOSAL_MARKER, proposalId)) {
            return Optional.empty();
        }
        SalaryRow p = store.find(proposalId).orElseThrow(() -> ApiException.notFound("Salary change request",
                proposalId));
        Map<String, Object> x = store.proposalPayload(p).orElseThrow(() ->
                ApiException.notFound("Salary change request", proposalId));
        if (!"APPROVED".equals(outcome)) {
            markers.mark(PROPOSAL_MARKER, proposalId, "core.WorkflowCompleted", "SKIPPED");
            return Optional.empty();
        }
        Employment emp = employments.require(p.employmentId());
        LocalDate d = p.validFrom();
        if (!emp.live() || !emp.covers(d)) {
            throw ApiException.unprocessable("outside-employment", "Employment " + emp.employeeNo()
                    + " no longer covers " + d);
        }
        UUID decisionId = null;
        String decisionNo = null;
        if (p.contractAppendixId() == null) {
            var decision = decisions.issue(new DecisionService.Issue(emp.legalEntityId(), "SALARY_CHANGE", emp.id(),
                    d, (String) x.get("signatoryName"), (String) x.get("signatoryTitle"), workflowInstanceId));
            decisionId = decision.id();
            decisionNo = decision.decisionNo();
        }
        SalaryRow row = new SalaryRow(appliedIdOf(proposalId), p.employmentId(), p.payBasis(), p.salaryKind(),
                p.baseSalaryAmount(), p.agreedNetAmount(), p.netGuaranteedScope(), p.siSalaryAmount(),
                p.currencyCode(), p.payScaleCode(), p.payScaleStepCode(), p.probationPct(), p.actionCode(),
                p.reasonCode(), decisionId, p.contractAppendixId(), d, p.validTo(), "APP", null, null, null, null,
                p.allowances());
        String kind;
        UUID supersededId = null;
        List<SalaryRow> line = store.line(emp.id());
        if ("CORRECTION".equals(x.get("kind"))) {
            UUID target = UUID.fromString(x.get("correctsRecordId").toString());
            SalaryRow old = line.stream().filter(r -> r.id().equals(target)).findFirst().orElseThrow(() ->
                    ApiException.conflict("stale-salary-record", "Salary record " + target
                            + " is no longer current; the correction cannot be applied"));
            String reason = (String) x.get("correctionReason");
            row = row.withPeriod(old.validFrom(), old.validTo()).withCorrectionReason(reason);
            store.replace(old.id(), reason, row);
            kind = "CORRECTION";
            supersededId = old.id();
        } else {
            Optional<SalaryRow> v = line.stream().filter(r -> r.covers(d)).findFirst();
            if (v.isPresent()) {
                SalaryRow old = v.get();
                if (old.validFrom().equals(d)) {
                    throw ApiException.unprocessable("salary-version-exists", "A salary version already starts on " + d);
                }
                store.replace(old.id(), null, old.withId(Ids.newId()).withPeriod(old.validFrom(), d));
                row = row.withPeriod(d, old.validTo());
                supersededId = old.id();
                kind = "CHANGE";
            } else if (line.isEmpty()) {
                kind = "HIRE";
            } else {
                throw ApiException.unprocessable("no-salary-at-date", "No salary record on " + d);
            }
            store.insert(row);
        }
        markers.mark(PROPOSAL_MARKER, proposalId, "core.WorkflowCompleted", "PROCESSED");
        audit.record(new BusinessAction("APPLY_SALARY_CHANGE", "hr", "salary_record", row.id(), emp.legalEntityId(),
                details("employmentId", emp.id(), "requestId", proposalId, "kind", kind, "decisionNo", decisionNo,
                        "contractAppendixId", p.contractAppendixId(), "workflowInstanceId", workflowInstanceId)));
        boolean inForce = !d.isAfter(clock.today());
        if (inForce) {
            publish(emp, row, kind, supersededId, false);
        }
        return Optional.of(new Applied(row, decisionId, decisionNo, inForce));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Views (US-PPL-037/044/045/046)

    /** The version valid on {@code asOf}, as known now or just before {@code knownBefore}; empty when none. */
    @HrTransactional
    public Optional<SalaryView> asOf(UUID employmentId, LocalDate asOf, Instant knownBefore, String purpose) {
        Employment emp = prepareView(employmentId, asOf);
        String reason = purposes.resolve(purpose);
        MaskLevel level = masking.apply(PeoplePermissions.COMPENSATION_VIEW);
        String known = knownBefore == null ? "s.superseded_at is null"
                : "s.recorded_at < :k and (s.superseded_at is null or s.superseded_at >= :k)";
        Map<String, Object> params = new HashMap<>();
        params.put("e", employmentId);
        params.put("d", asOf);
        if (knownBefore != null) {
            params.put("k", OffsetDateTime.ofInstant(knownBefore, ZoneOffset.UTC));
        }
        List<SalaryView> views = query("s.employment_id = :e and " + known + " and s.valid_during @> cast(:d as date)",
                params, level);
        auditView(emp, "AS_OF", reason, views, level);
        return views.stream().findFirst();
    }

    /** Salary history (diễn biến lương, US-PPL-046): every version incl. corrections, with decision / appendix. */
    @HrTransactional
    public List<SalaryView> history(UUID employmentId, String purpose) {
        Employment emp = prepareView(employmentId, clock.today());
        String reason = purposes.resolve(purpose);
        MaskLevel level = masking.apply(PeoplePermissions.COMPENSATION_VIEW);
        List<SalaryView> views = query("s.employment_id = :e", Map.of("e", employmentId), level);
        auditView(emp, "HISTORY", reason, views, level);
        return views;
    }

    @HrTransactional
    public SalaryView get(UUID employmentId, UUID salaryRecordId, String purpose) {
        Employment emp = prepareView(employmentId, clock.today());
        String reason = purposes.resolve(purpose);
        MaskLevel level = masking.apply(PeoplePermissions.COMPENSATION_VIEW);
        List<SalaryView> views = query("s.employment_id = :e and s.id = :id",
                Map.of("e", employmentId, "id", salaryRecordId), level);
        if (views.isEmpty()) {
            throw ApiException.notFound("Salary record", salaryRecordId);
        }
        auditView(emp, "DETAIL", reason, views, level);
        return views.getFirst();
    }

    /** Current salary of the employments in the caller's {@code hr.compensation.view} scope (list view). */
    @HrTransactional
    public List<SalaryListItem> list(UUID legalEntityId, LocalDate asOf, String purpose, int limit, int offset) {
        String reason = purposes.resolve(purpose);
        MaskLevel level = masking.apply(PeoplePermissions.COMPENSATION_VIEW);
        SqlFragment scope = access.scope(PeoplePermissions.COMPENSATION_VIEW, asOf, "s");
        record Row(UUID employmentId, String employeeNo, String fullName, UUID legalEntityId) {
        }
        var spec = db.jdbc().sql("select e.id, e.employee_no, p.full_name, e.legal_entity_id from "
                        + vn.yourhrm.people.internal.application.EmployeeAccess.EMPLOYMENT_SCOPE_JOIN
                        + " join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id"
                        + " where e.tenant_id = :t and e.status <> 'CANCELLED' and (cast(:le as uuid) is null"
                        + " or e.legal_entity_id = :le) and " + scope.sql()
                        + " order by e.employee_no limit :limit offset :offset")
                .param("t", TenantContext.current().tenantId()).param("asOf", asOf).param("le", legalEntityId)
                .param("limit", Math.min(Math.max(limit, 1), 500)).param("offset", Math.max(offset, 0))
                .params(scope.params());
        List<Row> rows = spec.query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class))).list();
        List<SalaryListItem> out = new ArrayList<>();
        for (Row r : rows) {
            List<SalaryView> current = query("s.employment_id = :e and s.superseded_at is null"
                    + " and s.valid_during @> cast(:d as date)", Map.of("e", r.employmentId(), "d", asOf), level);
            out.add(new SalaryListItem(r.employmentId(), r.employeeNo(), r.fullName(), r.legalEntityId(),
                    current.stream().findFirst().orElse(null)));
            audit.record(new BusinessAction("VIEW_SALARY", "hr", "employment", r.employmentId(), r.legalEntityId(),
                    details("view", "LIST", "purpose", reason, "maskLevel", level, "records",
                            current.stream().map(v -> v.id().toString()).toList())));
        }
        return out;
    }

    private Employment prepareView(UUID employmentId, LocalDate asOf) {
        access.require(PeoplePermissions.COMPENSATION_VIEW, employmentId, asOf);
        return employments.require(employmentId);
    }

    /** US-PPL-045: who (the actor of the audit row), when, which records, purpose; searchable by employment. */
    private void auditView(Employment emp, String view, String purpose, List<SalaryView> views, MaskLevel level) {
        audit.record(new BusinessAction("VIEW_SALARY", "hr", "employment", emp.id(), emp.legalEntityId(),
                details("view", view, "purpose", purpose, "maskLevel", level,
                        "records", views.stream().map(v -> v.id().toString()).toList())));
    }

    /** Reads through the masking views (amounts null unless the caller's mask level shows pay). */
    private List<SalaryView> query(String where, Map<String, ?> params, MaskLevel level) {
        List<SalaryView> views = db.jdbc().sql("""
                        select v.*, s.probation_pct, s.decision_id, s.contract_appendix_id, s.correction_reason,
                               s.superseded_by, d.decision_no, d.document_id as decision_document_id,
                               ap.appendix_no, ap.document_id as appendix_document_id
                          from hr.v_salary_record_masked v
                          join hr.salary_record s on s.tenant_id = v.tenant_id and s.id = v.id
                          left join hr.decision d on d.tenant_id = s.tenant_id and d.id = s.decision_id
                          left join hr.contract_appendix ap on ap.tenant_id = s.tenant_id and ap.id = s.contract_appendix_id
                         where s.tenant_id = :t and\s""" + where + " and " + SalaryStore.NOT_PROPOSAL
                        + " order by s.valid_from, s.recorded_at")
                .param("t", TenantContext.current().tenantId()).params(params)
                .query((rs, n) -> mapView(rs, level)).list();
        if (views.isEmpty()) {
            return views;
        }
        Map<UUID, List<Allowance>> allowances = new HashMap<>();
        db.jdbc().sql("""
                        select salary_record_id, component_code, amount, is_in_si_salary
                          from hr.v_salary_record_allowance_masked
                         where tenant_id = :t and salary_record_id in (:ids) order by component_code""")
                .param("t", TenantContext.current().tenantId()).param("ids", views.stream().map(SalaryView::id).toList())
                .query((rs, n) -> allowances.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new Allowance(rs.getString(2), rs.getBigDecimal(3), rs.getBoolean(4))))
                .list();
        List<SalaryView> out = new ArrayList<>();
        for (SalaryView v : views) {
            out.add(new SalaryView(v.id(), v.employmentId(), v.payBasis(), v.salaryKind(), v.baseSalaryAmount(),
                    v.agreedNetAmount(), v.netGuaranteedScope(), v.siSalaryAmount(), v.currencyCode(),
                    v.payScaleCode(), v.payScaleStepCode(), v.probationPct(), allowances.getOrDefault(v.id(), List.of()),
                    v.actionCode(), v.reasonCode(), v.validFrom(), v.validTo(), v.knownFrom(), v.knownTo(),
                    v.supersededBy(), v.correctionReason(), v.source(), v.decisionId(), v.decisionNo(),
                    v.decisionDocumentId(), v.contractAppendixId(), v.appendixNo(), v.appendixDocumentId(),
                    v.amountsMasked()));
        }
        return out;
    }

    private SalaryView mapView(ResultSet rs, MaskLevel level) throws SQLException {
        String scope = rs.getString("net_guaranteed_scope");
        List<String> scopeList = null;
        if (scope != null) {
            List<?> raw = json.read(scope, List.class);
            scopeList = raw.stream().map(Object::toString).toList();
        }
        OffsetDateTime recorded = rs.getObject("recorded_at", OffsetDateTime.class);
        OffsetDateTime superseded = rs.getObject("superseded_at", OffsetDateTime.class);
        return new SalaryView(rs.getObject("id", UUID.class), rs.getObject("employment_id", UUID.class),
                rs.getString("pay_basis"), rs.getString("salary_kind"), rs.getBigDecimal("base_salary_amount"),
                rs.getBigDecimal("agreed_net_amount"), scopeList, rs.getBigDecimal("si_salary_amount"),
                rs.getString("currency_code"), rs.getString("pay_scale_code"), rs.getString("pay_scale_step_code"),
                rs.getBigDecimal("probation_pct"), List.of(), rs.getString("action_code"), rs.getString("reason_code"),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                recorded == null ? null : recorded.toInstant(), superseded == null ? null : superseded.toInstant(),
                rs.getObject("superseded_by", UUID.class), rs.getString("correction_reason"), rs.getString("source"),
                rs.getObject("decision_id", UUID.class), rs.getString("decision_no"),
                rs.getObject("decision_document_id", UUID.class), rs.getObject("contract_appendix_id", UUID.class),
                rs.getString("appendix_no"), rs.getObject("appendix_document_id", UUID.class), !level.showsPay());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Events and the daily job

    /** Publishes {@code people.SalaryChanged} of versions recorded before their effective date that is now due. */
    @HrTransactional
    public int publishDueChanges(LocalDate today, int lookbackDays) {
        int n = 0;
        List<SalaryRow> due = store.where("s.superseded_at is null and s.valid_from <= :today and s.valid_from > :from"
                + " and s.valid_from > cast((s.recorded_at at time zone 'Asia/Ho_Chi_Minh') as date)",
                Map.of("today", today, "from", today.minusDays(lookbackDays)));
        for (SalaryRow row : due) {
            markers.lock(DUE_MARKER, row.id());
            if (markers.exists(DUE_MARKER, row.id())) {
                continue;
            }
            Employment emp = employments.require(row.employmentId());
            if ("CANCELLED".equals(emp.status())) {
                markers.mark(DUE_MARKER, row.id(), "people.SalaryChanged", "SKIPPED");
                continue;
            }
            List<SalaryRow> line = store.line(emp.id());
            boolean first = line.stream().noneMatch(r -> row.validFrom().equals(r.validTo()));
            UUID previous = line.stream().filter(r -> row.validFrom().equals(r.validTo())).map(SalaryRow::id)
                    .findFirst().orElse(null);
            publish(emp, row, first ? "HIRE" : "CHANGE", previous, "OPENING_LOAD".equals(row.source()));
            markers.mark(DUE_MARKER, row.id(), "people.SalaryChanged", "PROCESSED");
            n++;
        }
        return n;
    }

    private void publish(Employment emp, SalaryRow row, String kind, UUID supersededId, boolean initialLoad) {
        Retro r = initialLoad ? Retro.NO : retro.of(emp.legalEntityId(), row.validFrom());
        String decisionNo = row.decisionId() == null ? null
                : decisions.find(row.decisionId()).map(DecisionService.Decision::decisionNo).orElse(null);
        String appendixNo = row.contractAppendixId() == null ? null : db.jdbc()
                .sql("select appendix_no from hr.contract_appendix where tenant_id = :t and id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", row.contractAppendixId())
                .query(String.class).optional().orElse(null);
        TenantContext ctx = TenantContext.current();
        outbox.publish(new SalaryChanged(ctx.tenantId(), emp.legalEntityId(), emp.personId(), emp.id(),
                row.validFrom(), kind, row.id(), supersededId, row.payBasis(), row.salaryKind(),
                row.baseSalaryAmount(), row.agreedNetAmount(), row.netGuaranteedScope(), row.siSalaryAmount(),
                row.currencyCode(), row.payScaleCode(), row.payScaleStepCode(), row.probationPct(), row.allowances(),
                row.actionCode(), row.reasonCode(), row.validFrom(), row.validTo(), row.decisionId(), decisionNo,
                row.contractAppendixId(), appendixNo, r.retro(), r.earliestAffectedPeriod(), initialLoad));
    }

    // ---------------------------------------------------------------------------------------------------------------

    private UUID currentOrgUnit(UUID employmentId, LocalDate d) {
        return db.jdbc().sql("select pa.org_unit_id from "
                        + vn.yourhrm.people.internal.application.EmployeeAccess.EMPLOYMENT_SCOPE_JOIN
                        + " where e.tenant_id = :t and e.id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", employmentId).param("asOf", d)
                .query(UUID.class).optional().orElse(null);
    }

    private void requireAppendix(UUID employmentId, UUID appendixId) {
        boolean ok = db.jdbc().sql("""
                        select exists (select 1 from hr.contract_appendix a
                                         join hr.contract c on c.tenant_id = a.tenant_id and c.id = a.contract_id
                                        where a.tenant_id = :t and a.id = :a and c.employment_id = :e
                                          and a.status <> 'CANCELLED')""")
                .param("t", TenantContext.current().tenantId()).param("a", appendixId).param("e", employmentId)
                .query(Boolean.class).single();
        if (!ok) {
            throw ApiException.unprocessable("unknown-appendix", "Contract appendix " + appendixId
                    + " does not exist for this employment");
        }
    }

    private static SalaryRow newRow(UUID id, UUID employmentId, SalaryTerms t, String action, String reason,
            UUID decisionId, UUID appendixId, LocalDate from, LocalDate to, String source, String correctionReason) {
        return new SalaryRow(id, employmentId, t.payBasis(), t.salaryKind(), t.baseSalaryAmount(), t.agreedNetAmount(),
                t.netGuaranteedScope(), t.siSalaryAmount(), t.currencyCode(), t.payScaleCode(), t.payScaleStepCode(),
                t.probationPct(), action, reason, decisionId, appendixId, from, to, source, null, null, null,
                correctionReason, t.allowances());
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v instanceof UUID u ? u.toString() : v);
        }
    }

    private static Map<String, String> details(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(kv[i].toString(), kv[i + 1].toString());
            }
        }
        return m;
    }
}
