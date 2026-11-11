package vn.yourhrm.people.internal.application.assignment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.AssignmentEvents.AssignmentChangeCancelled;
import vn.yourhrm.people.api.AssignmentEvents.AssignmentChanged;
import vn.yourhrm.people.api.AssignmentEvents.CostShare;
import vn.yourhrm.people.api.AssignmentEvents.TemporaryTransferChanged;
import vn.yourhrm.people.api.AssignmentEvents.WageProtection;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.application.integration.HireApprovalGate;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.assignment.EmploymentFacts.Employment;
import vn.yourhrm.people.internal.application.assignment.RetroFlags.Retro;
import vn.yourhrm.people.internal.domain.assignment.ChangeReason;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.assignment.AssignmentRow;
import vn.yourhrm.people.internal.infra.assignment.AssignmentStore;
import vn.yourhrm.people.internal.infra.assignment.ProcessedMarkers;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartResult;

/**
 * Date-effective assignments (EP-PPL-04; BR-PPL-030/031/032/035/037, NFR-PPL-001). {@code hr.assignment} is
 * bi-temporal: a change closes the version in force by superseding it and inserting a copy that ends the day before
 * the change, then inserts the new version, so a line never has gaps or overlaps and history is never overwritten
 * (US-PPL-033). A job change is a request with a reason (US-PPL-035) that goes through the reason's Core workflow and
 * on approval creates the new version and the decision (US-PPL-036); future-dated changes take effect through the
 * daily job and can be cancelled before (US-PPL-038); dated events carry the retro flag (US-PPL-039); temporary
 * transfers follow Art. 29 (US-PPL-040). An assignment has no pay group and no work schedule (BR-PPL-031).
 *
 * <p>Every request is kept as a proposal row (see {@link AssignmentStore}); the version it creates has the id
 * {@link #appliedIdOf(UUID)} of the proposal, so the request (appendix, consent document, wage protection) stays
 * linked to the change it produced.
 */
@Service
public class AssignmentService {

    /** Consumer name of the workflow-outcome markers of job-change proposals. */
    static final String PROPOSAL_MARKER = "hr.assignment.proposal";

    /**
     * The attributes of an assignment version, PUT-style (every version carries the full set). {@code costSplit}
     * empty = 100 % on {@code costCentreId}; {@code managerEmploymentId} null with a position = derived from the
     * manager position's incumbent (US-PPL-034); {@code fte} null = 1.
     */
    public record Attributes(UUID orgUnitId, UUID positionId, UUID jobTitleId, UUID gradeId, UUID managerEmploymentId,
            UUID locationId, UUID costCentreId, List<CostShare> costSplit, BigDecimal fte) {
    }

    /** A version with its valid time ({@code validTo} exclusive) and transaction time (known from / to). */
    public record AssignmentView(UUID id, UUID assignmentKey, UUID employmentId, String assignmentType,
            UUID legalEntityId, UUID payingLegalEntityId, UUID orgUnitId, UUID positionId, UUID jobTitleId,
            UUID gradeId, UUID managerEmploymentId, UUID locationId, UUID costCentreId, List<CostShare> costSplit,
            BigDecimal fte, String actionCode, String reasonCode, UUID decisionId, String decisionNo,
            LocalDate validFrom, LocalDate validTo, Instant knownFrom, Instant knownTo, UUID supersededBy,
            String correctionReason, String source) {
    }

    /**
     * A job-change request (US-PPL-036). {@code endDate} (inclusive) only for a temporary transfer;
     * {@code noticeDate} = when the employee was told (default today); {@code contractAppendixId} = the appendix that
     * carries changed contract terms (built by the contract stream).
     */
    public record ChangeRequest(String reasonCode, LocalDate effectiveDate, LocalDate endDate, Attributes attributes,
            UUID contractAppendixId, UUID consentDocumentId, LocalDate noticeDate, String signatoryName,
            String signatoryTitle, String comment) {
    }

    /** {@code status}: PENDING_APPROVAL, APPLIED (in force) or SCHEDULED (approved, effective in the future). */
    public record ChangeResult(UUID requestId, String status, UUID workflowInstanceId, AssignmentView assignment,
            UUID decisionId, String decisionNo, List<Warning> warnings) {
    }

    private final HrDb db;
    private final AssignmentStore store;
    private final ProcessedMarkers markers;
    private final EmploymentFacts employments;
    private final EmployeeAccess access;
    private final ChangeReasonCatalogue reasons;
    private final AssignmentReferences references;
    private final TemporaryTransferPolicy transfers;
    private final DecisionService decisions;
    private final WorkflowService workflow;
    private final HrOutbox outbox;
    private final RetroFlags retro;
    private final AuditLog audit;
    private final BusinessClock clock;
    /** Integration fix F1: holds the first events of a hire that is not approved yet. */
    private final HireApprovalGate hireGate;

    public AssignmentService(HrDb db, AssignmentStore store, ProcessedMarkers markers, EmploymentFacts employments,
            EmployeeAccess access, ChangeReasonCatalogue reasons, AssignmentReferences references,
            TemporaryTransferPolicy transfers, DecisionService decisions, WorkflowService workflow, HrOutbox outbox,
            RetroFlags retro, AuditLog audit, BusinessClock clock, HireApprovalGate hireGate) {
        this.hireGate = hireGate;
        this.db = db;
        this.store = store;
        this.markers = markers;
        this.employments = employments;
        this.access = access;
        this.reasons = reasons;
        this.references = references;
        this.transfers = transfers;
        this.decisions = decisions;
        this.workflow = workflow;
        this.outbox = outbox;
        this.retro = retro;
        this.audit = audit;
        this.clock = clock;
    }

    /** The id of the version created by proposal {@code proposalId} (and back): an involution keeping the UUID layout. */
    public static UUID appliedIdOf(UUID proposalId) {
        return new UUID(proposalId.getMostSignificantBits(), proposalId.getLeastSignificantBits() ^ 0x0000_5EED_0000_0001L);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Internal API for the hire and opening-balance streams

    /**
     * The first PRIMARY line of an employment from {@code validFrom} (hire, opening-balance import). Runs in the
     * caller's People transaction; no permission check (the caller authorised its own request). Publishes
     * {@code people.AssignmentChanged} (change kind HIRE, {@code initialLoad} for OPENING_LOAD) now when
     * {@code validFrom} has come, otherwise the daily job publishes it on that date.
     *
     * @param decisionId the hire decision, may be null (opening balance)
     * @param source     APP, OPENING_LOAD, REC_HANDOFF or IMPORT
     */
    @HrTransactional
    public AssignmentView createInitial(UUID employmentId, Attributes attributes, LocalDate validFrom, UUID decisionId,
            String source) {
        Employment emp = employments.require(employmentId);
        if (store.current(employmentId).stream().anyMatch(r -> "PRIMARY".equals(r.assignmentType()))) {
            throw ApiException.conflict("assignment-exists", "Employment " + emp.employeeNo()
                    + " already has a primary assignment; use a job change");
        }
        var resolved = references.resolve(emp, attributes, validFrom);
        AssignmentRow row = newRow(Ids.newId(), Ids.newId(), emp, "PRIMARY", resolved.attributes(), "HIRE", null,
                decisionId, validFrom, null, source == null ? "APP" : source);
        store.insert(row);
        // F1 (integration): a hire that still waits for approval is announced by HireService on approval /
        // activation (releaseHeldInitialEvents); the due marker makes every later publisher skip this version.
        if (!validFrom.isAfter(clock.today()) && !hireGate.pending(employmentId)) {
            publishChanged(emp, row, "HIRE", null, null, "OPENING_LOAD".equals(source));
            markers.mark(AssignmentDueJob.DUE_MARKER, row.id(), "people.AssignmentChanged", "PROCESSED");
        }
        return view(store.find(row.id()).orElseThrow());
    }

    /**
     * Integration fix F1: publishes the {@code AssignmentChanged} (and temporary-transfer) events of the current
     * versions of {@code employmentId} in force on or before {@code through} that were held while the hire waited for
     * approval. Called by the hire flow on approval / activation; idempotent through the daily-job markers (a version
     * the daily job or {@link #createInitial} already published is skipped). Runs in the caller's People transaction.
     *
     * @return number of versions published
     */
    @HrTransactional
    public int releaseHeldInitialEvents(UUID employmentId, LocalDate through) {
        Employment emp = employments.require(employmentId);
        if ("CANCELLED".equals(emp.status()) || hireGate.pending(employmentId)) {
            return 0;
        }
        int n = 0;
        for (AssignmentRow row : store.current(employmentId).stream()
                .filter(r -> !r.validFrom().isAfter(through))
                .sorted(java.util.Comparator.comparing(AssignmentRow::validFrom)).toList()) {
            markers.lock(AssignmentDueJob.DUE_MARKER, row.id());
            if (markers.exists(AssignmentDueJob.DUE_MARKER, row.id())) {
                continue;
            }
            publishDue(emp, row, false);
            markers.mark(AssignmentDueJob.DUE_MARKER, row.id(), "people.AssignmentChanged", "PROCESSED");
            n++;
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Job change (US-PPL-036/038/040)

    @HrTransactional
    public ChangeResult requestChange(UUID employmentId, ChangeRequest req) {
        LocalDate today = clock.today();
        access.require(PeoplePermissions.ASSIGNMENT_UPDATE, employmentId, req.effectiveDate());
        Employment emp = employments.require(employmentId);
        ChangeReason reason = reasons.require(req.reasonCode(), today);
        if (!emp.live()) {
            throw ApiException.unprocessable("employment-not-live", "Employment " + emp.employeeNo() + " is "
                    + emp.status());
        }
        if (!emp.covers(req.effectiveDate())) {
            throw ApiException.unprocessable("outside-employment", "The effective date is outside the employment");
        }
        AssignmentRow primary = store.primaryOn(employmentId, req.effectiveDate()).orElseThrow(() ->
                ApiException.unprocessable("no-assignment-at-date", "Employment " + emp.employeeNo()
                        + " has no primary assignment on " + req.effectiveDate()));
        if ("APPENDIX".equals(reason.documentKind()) && req.contractAppendixId() == null) {
            throw ApiException.unprocessable("appendix-required", "Change reason " + reason.code()
                    + " is issued by a contract appendix: contractAppendixId is required");
        }
        if (req.contractAppendixId() != null) {
            requireAppendix(employmentId, req.contractAppendixId());
        }
        var resolved = references.resolve(emp, req.attributes(), req.effectiveDate());
        List<Warning> warnings = new ArrayList<>(resolved.warnings());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", reason.code());
        payload.put("documentKind", reason.documentKind());
        payload.put("decisionTypeCode", reason.decisionTypeCode());
        payload.put("processTypeCode", reason.processTypeCode());
        put(payload, "contractAppendixId", req.contractAppendixId());
        put(payload, "signatoryName", req.signatoryName());
        put(payload, "signatoryTitle", req.signatoryTitle());
        put(payload, "comment", req.comment());
        put(payload, "previousManagerEmploymentId", primary.managerEmploymentId());
        payload.put("managerDerived", resolved.managerDerived());

        UUID key;
        LocalDate validTo = null;
        if (reason.temporaryTransfer()) {
            LocalDate end = req.endDate();
            var check = transfers.check(emp, resolved.attributes().locationId(), req.effectiveDate(), end,
                    req.noticeDate() == null ? today : req.noticeDate(), req.consentDocumentId());
            validTo = end.plusDays(1);
            requireNoOtherTransfer(employmentId, req.effectiveDate(), validTo);
            key = Ids.newId();
            put(payload, "consentDocumentId", req.consentDocumentId());
            payload.put("workingDaysInYear", check.workingDaysInStartYear());
            payload.put("wageFloorPct", check.wageProtection().newWageMinPctOfPrevious());
            payload.put("previousWageWorkingDays", check.wageProtection().previousWageWorkingDays());
        } else {
            if (req.endDate() != null) {
                throw ApiException.unprocessable("invalid-period", "endDate is only allowed for a temporary transfer");
            }
            if (primary.validFrom().equals(req.effectiveDate())) {
                throw ApiException.unprocessable("assignment-version-exists", "A version already starts on "
                        + req.effectiveDate() + "; correct it or cancel it instead");
            }
            key = primary.assignmentKey();
        }
        UUID proposalId = Ids.newId();
        AssignmentRow proposal = newRow(proposalId, key, emp, reason.assignmentType(), resolved.attributes(),
                reason.actionCode(), reason.code(), null, req.effectiveDate(), validTo, "APP");
        store.insertProposal(proposal, payload);

        if (reason.processTypeCode() == null) {
            Applied applied = apply(proposalId, null);
            return result(proposalId, applied, null, warnings);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("employeeNo", emp.employeeNo());
        summary.put("employeeName", emp.fullName());
        summary.put("reasonCode", reason.code());
        summary.put("effectiveDate", req.effectiveDate().toString());
        if (req.endDate() != null) {
            summary.put("endDate", req.endDate().toString());
        }
        summary.put("orgUnitId", resolved.attributes().orgUnitId().toString());
        StartResult started = workflow.start(new StartRequest(reason.processTypeCode(), "hr.assignment", proposalId,
                null, "hr.assignment:" + proposalId, null, emp.personId(), emp.legalEntityId(), primary.orgUnitId(),
                primary.locationId(), null, summary,
                Map.of("reasonCode", reason.code(), "actionCode", reason.actionCode()), Map.of()));
        audit.record(new BusinessAction("REQUEST_JOB_CHANGE", "hr", "assignment", proposalId, emp.legalEntityId(),
                Map.of("employmentId", employmentId.toString(), "reasonCode", reason.code(),
                        "effectiveDate", req.effectiveDate().toString(),
                        "workflowInstanceId", started.instanceId().toString())));
        if ("APPROVED".equals(started.status())) {
            Optional<Applied> applied = resolveProposal(proposalId, started.instanceId(), "APPROVED");
            if (applied.isPresent()) {
                return result(proposalId, applied.get(), started.instanceId(), warnings);
            }
        }
        return new ChangeResult(proposalId, "PENDING_APPROVAL", started.instanceId(), null, null, null, warnings);
    }

    /**
     * Applies (APPROVED) or closes (any other outcome) a job-change proposal once its workflow completed; idempotent
     * per proposal. Runs in the People transaction of the caller (the workflow listener opens a new one).
     *
     * @return the change applied by THIS call (empty when already handled or not approved)
     */
    @HrTransactional
    public Optional<Applied> resolveProposal(UUID proposalId, UUID workflowInstanceId, String outcome) {
        markers.lock(PROPOSAL_MARKER, proposalId);
        if (markers.exists(PROPOSAL_MARKER, proposalId)) {
            return Optional.empty();
        }
        if (!"APPROVED".equals(outcome)) {
            store.find(proposalId).flatMap(store::proposalPayload).orElseThrow(() ->
                    ApiException.notFound("Assignment change request", proposalId));
            markers.mark(PROPOSAL_MARKER, proposalId, "core.WorkflowCompleted", "SKIPPED");
            return Optional.empty();
        }
        Applied applied = apply(proposalId, workflowInstanceId);
        markers.mark(PROPOSAL_MARKER, proposalId, "core.WorkflowCompleted", "PROCESSED");
        return Optional.of(applied);
    }

    /**
     * Integration fix F4 (BR-PPL-055, US-PPL-053): a signed contract appendix that changes assignment terms (org unit,
     * position, job title, work location; ids) creates the new PRIMARY version itself, linked to the appendix (document
     * kind APPENDIX: no decision, no workflow; the signed appendix is the agreement). Unchanged attributes are carried
     * over from the version in force; the reference checks of a job change apply ({@link AssignmentReferences}). The
     * reason is LOCATION_CHANGE (location only), TRANSFER (org unit) or JOB_TITLE_CHANGE. Runs in the caller's People
     * transaction, no permission check (the appendix flow authorised).
     */
    @HrTransactional
    public Applied applyFromAppendix(UUID employmentId, UUID appendixId, LocalDate effectiveDate, UUID orgUnitId,
            UUID positionId, UUID jobTitleId, UUID locationId) {
        Employment emp = employments.require(employmentId);
        if (!emp.live() || !emp.covers(effectiveDate)) {
            throw ApiException.unprocessable("outside-employment", "The appendix effective date is outside employment "
                    + emp.employeeNo());
        }
        AssignmentRow primary = store.primaryOn(employmentId, effectiveDate).orElseThrow(() ->
                ApiException.unprocessable("no-assignment-at-date", "Employment " + emp.employeeNo()
                        + " has no primary assignment on " + effectiveDate));
        if (primary.validFrom().equals(effectiveDate)) {
            throw ApiException.unprocessable("assignment-version-exists", "A version already starts on "
                    + effectiveDate + "; the appendix cannot add another one");
        }
        boolean positionChanged = positionId != null && !positionId.equals(primary.positionId());
        Attributes attributes = new Attributes(orgUnitId != null ? orgUnitId : primary.orgUnitId(),
                positionId != null ? positionId : primary.positionId(),
                jobTitleId != null ? jobTitleId : primary.jobTitleId(), primary.gradeId(),
                positionChanged ? null : primary.managerEmploymentId(),
                locationId != null ? locationId : primary.locationId(), primary.costCentreId(), primary.costSplit(),
                primary.fte());
        var resolved = references.resolve(emp, attributes, effectiveDate);
        String reasonCode = orgUnitId != null && !orgUnitId.equals(primary.orgUnitId()) ? "TRANSFER"
                : orgUnitId == null && positionId == null && jobTitleId == null ? "LOCATION_CHANGE" : "JOB_TITLE_CHANGE";
        String actionCode = ChangeReason.DEFAULTS.stream().filter(r -> r.code().equals(reasonCode))
                .map(ChangeReason::actionCode).findFirst().orElse(reasonCode);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", reasonCode);
        payload.put("documentKind", "APPENDIX");
        payload.put("contractAppendixId", appendixId.toString());
        put(payload, "previousManagerEmploymentId", primary.managerEmploymentId());
        payload.put("managerDerived", resolved.managerDerived());
        UUID proposalId = Ids.newId();
        store.insertProposal(newRow(proposalId, primary.assignmentKey(), emp, "PRIMARY", resolved.attributes(),
                actionCode, reasonCode, null, effectiveDate, null, "APP"), payload);
        Applied applied = apply(proposalId, null);
        markers.mark(PROPOSAL_MARKER, proposalId, "people.ContractChanged", "PROCESSED");
        return applied;
    }

    /** What applying a proposal produced. */
    public record Applied(AssignmentRow row, Employment employment, UUID decisionId, String decisionNo,
            boolean inForce) {
    }

    private Applied apply(UUID proposalId, UUID workflowInstanceId) {
        AssignmentRow p = store.find(proposalId).orElseThrow(() ->
                ApiException.notFound("Assignment change request", proposalId));
        Map<String, Object> x = store.proposalPayload(p).orElseThrow(() ->
                ApiException.notFound("Assignment change request", proposalId));
        Employment emp = employments.require(p.employmentId());
        LocalDate d = p.validFrom();
        if (!emp.live() || !emp.covers(d)) {
            throw ApiException.unprocessable("outside-employment", "Employment " + emp.employeeNo()
                    + " no longer covers " + d + " (" + emp.status() + ")");
        }
        UUID decisionId = null;
        String decisionNo = null;
        String documentKind = (String) x.get("documentKind");
        if (documentKind != null && documentKind.startsWith("DECISION")) {
            var decision = decisions.issue(new DecisionService.Issue(emp.legalEntityId(),
                    (String) x.get("decisionTypeCode"), emp.id(), d, (String) x.get("signatoryName"),
                    (String) x.get("signatoryTitle"), workflowInstanceId));
            decisionId = decision.id();
            decisionNo = decision.decisionNo();
        }
        AssignmentRow row = new AssignmentRow(appliedIdOf(proposalId), p.assignmentKey(), p.employmentId(),
                p.assignmentType(), p.legalEntityId(), p.payingLegalEntityId(), p.positionId(), p.orgUnitId(),
                p.jobTitleId(), p.gradeId(), p.managerEmploymentId(), p.locationId(), p.costCentreId(), p.costSplit(),
                p.fte(), p.actionCode(), p.reasonCode(), decisionId, d, p.validTo(), p.source(), null, null, null,
                null);
        if ("TEMP_TRANSFER".equals(p.assignmentType())) {
            requireNoOtherTransfer(emp.id(), d, p.validTo());
            store.insert(row);
        } else {
            List<AssignmentRow> line = store.line(p.assignmentKey());
            AssignmentRow v = line.stream().filter(r -> r.covers(d)).findFirst().orElseThrow(() ->
                    ApiException.unprocessable("no-assignment-at-date", "Employment " + emp.employeeNo()
                            + " has no primary assignment on " + d));
            if (v.validFrom().equals(d)) {
                throw ApiException.unprocessable("assignment-version-exists", "A version already starts on " + d);
            }
            store.replace(v.id(), null, v.withId(Ids.newId()).withPeriod(v.validFrom(), d));
            row = row.withPeriod(d, v.validTo());
            store.insert(row);
        }
        audit.record(new BusinessAction("APPLY_JOB_CHANGE", "hr", "assignment", row.id(), emp.legalEntityId(),
                details("employmentId", emp.id(), "requestId", proposalId, "reasonCode", p.reasonCode(),
                        "decisionNo", decisionNo, "workflowInstanceId", workflowInstanceId)));
        boolean inForce = !d.isAfter(clock.today());
        if (inForce) {
            publishDue(emp, row, false);
        }
        return new Applied(row, emp, decisionId, decisionNo, inForce);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Correction and cancellation (US-PPL-037/038)

    /**
     * Corrects a version (a data error, US-PPL-037): it is superseded with {@code reason} and replaced by a version
     * with the same validity and the corrected attributes. The superseded version stays queryable as known before.
     */
    @HrTransactional
    public AssignmentView correct(UUID employmentId, UUID assignmentId, String reason, Attributes attributes) {
        access.require(PeoplePermissions.ASSIGNMENT_UPDATE, employmentId, clock.today());
        AssignmentRow v = currentVersion(employmentId, assignmentId);
        requireReason(reason);
        Employment emp = employments.require(employmentId);
        var resolved = references.resolve(emp, attributes, v.validFrom());
        Attributes a = resolved.attributes();
        AssignmentRow replacement = new AssignmentRow(Ids.newId(), v.assignmentKey(), v.employmentId(),
                v.assignmentType(), v.legalEntityId(), v.payingLegalEntityId(), a.positionId(), a.orgUnitId(),
                a.jobTitleId(), a.gradeId(), a.managerEmploymentId(), a.locationId(), a.costCentreId(), a.costSplit(),
                a.fte(), v.actionCode(), v.reasonCode(), v.decisionId(), v.validFrom(), v.validTo(), v.source(), null,
                null, null, reason);
        store.replace(v.id(), reason, replacement);
        audit.record(new BusinessAction("CORRECT", "hr", "assignment", replacement.id(), emp.legalEntityId(),
                details("employmentId", employmentId, "supersededId", v.id(), "reason", reason)));
        if (!v.validFrom().isAfter(clock.today())) {
            publishChanged(emp, replacement, "CORRECTION", v.managerEmploymentId(), null, false);
        }
        return view(store.find(replacement.id()).orElseThrow());
    }

    /**
     * Cancels an approved change before its effective date (US-PPL-038): the future version is superseded, the
     * version before it runs on, its decision is cancelled and {@code people.AssignmentChangeCancelled} is published.
     */
    @HrTransactional
    public AssignmentView cancelFutureChange(UUID employmentId, UUID assignmentId, String reason) {
        access.require(PeoplePermissions.ASSIGNMENT_UPDATE, employmentId, clock.today());
        AssignmentRow f = currentVersion(employmentId, assignmentId);
        requireReason(reason);
        if (!f.validFrom().isAfter(clock.today())) {
            throw ApiException.unprocessable("change-already-effective", "The change took effect on "
                    + f.validFrom() + "; correct it or make a new change instead");
        }
        Employment emp = employments.require(employmentId);
        List<AssignmentRow> line = store.line(f.assignmentKey());
        Optional<AssignmentRow> previous = line.stream().filter(r -> f.validFrom().equals(r.validTo())).findFirst();
        if (previous.isEmpty() && "PRIMARY".equals(f.assignmentType())) {
            throw ApiException.unprocessable("cannot-cancel-first-version",
                    "The first version of an employment's primary assignment is cancelled with the hire");
        }
        store.supersede(f.id(), "CANCELLED: " + reason);
        previous.ifPresent(p -> store.replace(p.id(), null, p.withId(Ids.newId()).withPeriod(p.validFrom(),
                f.validTo())));
        if (f.decisionId() != null) {
            decisions.find(f.decisionId()).filter(dd -> !"CANCELLED".equals(dd.status()))
                    .ifPresent(dd -> decisions.cancel(dd.id(), dd.version()));
        }
        TenantContext ctx = TenantContext.current();
        outbox.publish(new AssignmentChangeCancelled(ctx.tenantId(), emp.legalEntityId(), emp.personId(), emp.id(),
                f.validFrom(), f.id(), f.assignmentKey(), f.assignmentType(), f.reasonCode(), f.decisionId(), reason));
        if ("TEMP_TRANSFER".equals(f.assignmentType())) {
            Map<String, Object> x = proposalOf(f).orElse(Map.of());
            outbox.publish(transferEvent(emp, f, "CANCELLED", x, Retro.NO));
        }
        audit.record(new BusinessAction("CANCEL_JOB_CHANGE", "hr", "assignment", f.id(), emp.legalEntityId(),
                details("employmentId", employmentId, "reason", reason)));
        return view(store.find(f.id()).orElseThrow());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Queries (US-PPL-037; NFR-PPL-001)

    /**
     * Versions valid on {@code asOf}: as currently known, or as known just before {@code knownBefore} (exclusive).
     */
    @HrTransactional(readOnly = true)
    public List<AssignmentView> asOf(UUID employmentId, LocalDate asOf, Instant knownBefore) {
        access.require(PeoplePermissions.EMPLOYEE_VIEW, employmentId, asOf);
        return views(store.asOf(employmentId, asOf, knownBefore));
    }

    /** Every version ever recorded, corrections and superseded versions included, with known-from / known-to. */
    @HrTransactional(readOnly = true)
    public List<AssignmentView> history(UUID employmentId) {
        access.require(PeoplePermissions.EMPLOYEE_VIEW, employmentId, clock.today());
        return views(store.history(employmentId));
    }

    @HrTransactional(readOnly = true)
    public AssignmentView get(UUID employmentId, UUID assignmentId) {
        AssignmentRow r = store.find(assignmentId).filter(x -> x.employmentId().equals(employmentId))
                .filter(x -> store.proposalPayload(x).isEmpty())
                .orElseThrow(() -> ApiException.notFound("Assignment", assignmentId));
        access.require(PeoplePermissions.EMPLOYEE_VIEW, employmentId, r.validFrom());
        return view(r);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Events (also used by the daily job)

    /**
     * Publishes the events of a version that is in force: {@code AssignmentChanged} and, for a temporary transfer,
     * {@code TemporaryTransferChanged} STARTED (and ENDED when it is already over).
     */
    void publishDue(Employment emp, AssignmentRow row, boolean fromJob) {
        Map<String, Object> x = proposalOf(row).orElse(Map.of());
        String kind = store.line(row.assignmentKey()).stream().anyMatch(r -> row.validFrom().equals(r.validTo()))
                || "TEMP_TRANSFER".equals(row.assignmentType()) ? "CHANGE" : "HIRE";
        UUID previousManager = x.get("previousManagerEmploymentId") == null ? null
                : UUID.fromString(x.get("previousManagerEmploymentId").toString());
        UUID appendix = x.get("contractAppendixId") == null ? null
                : UUID.fromString(x.get("contractAppendixId").toString());
        Retro r = publishChanged(emp, row, kind, previousManager, appendix, "OPENING_LOAD".equals(row.source()));
        if ("TEMP_TRANSFER".equals(row.assignmentType())) {
            outbox.publish(transferEvent(emp, row, "STARTED", x, r));
            if (!fromJob && row.validTo() != null && !row.validTo().isAfter(clock.today())) {
                outbox.publish(transferEvent(emp, row, "ENDED", x, retro.of(emp.legalEntityId(), row.validTo())));
                markers.mark(AssignmentDueJob.ENDED_MARKER, row.id(), "people.TemporaryTransferChanged", "PROCESSED");
            }
        }
    }

    void publishTransferEnded(Employment emp, AssignmentRow row) {
        Map<String, Object> x = proposalOf(row).orElse(Map.of());
        outbox.publish(transferEvent(emp, row, "ENDED", x, retro.of(emp.legalEntityId(), row.validTo())));
    }

    private Retro publishChanged(Employment emp, AssignmentRow row, String kind, UUID previousManager, UUID appendix,
            boolean initialLoad) {
        Retro r = initialLoad ? Retro.NO : retro.of(emp.legalEntityId(), row.validFrom());
        String decisionNo = row.decisionId() == null ? null
                : decisions.find(row.decisionId()).map(DecisionService.Decision::decisionNo).orElse(null);
        TenantContext ctx = TenantContext.current();
        outbox.publish(new AssignmentChanged(ctx.tenantId(), emp.legalEntityId(), emp.personId(), emp.id(),
                row.validFrom(), kind, row.id(), row.assignmentKey(), row.assignmentType(), row.actionCode(),
                row.reasonCode(), row.orgUnitId(), row.positionId(), row.jobTitleId(), row.gradeId(),
                row.managerEmploymentId(), previousManager, row.locationId(), row.costCentreId(), row.costSplit(),
                row.fte(), row.validFrom(), row.validTo(), row.decisionId(), decisionNo, appendix, r.retro(),
                r.earliestAffectedPeriod(), initialLoad));
        return r;
    }

    private TemporaryTransferChanged transferEvent(Employment emp, AssignmentRow row, String status,
            Map<String, Object> x, Retro r) {
        TenantContext ctx = TenantContext.current();
        WageProtection protection = x.get("wageFloorPct") == null ? null
                : new WageProtection(((Number) x.get("previousWageWorkingDays")).intValue(),
                        new BigDecimal(x.get("wageFloorPct").toString()), true);
        int days = x.get("workingDaysInYear") == null ? 0 : ((Number) x.get("workingDaysInYear")).intValue();
        UUID consent = x.get("consentDocumentId") == null ? null : UUID.fromString(x.get("consentDocumentId").toString());
        LocalDate effective = "ENDED".equals(status) ? row.validTo() : row.validFrom();
        return new TemporaryTransferChanged(ctx.tenantId(), emp.legalEntityId(), emp.personId(), emp.id(), effective,
                status, row.id(), row.assignmentKey(), row.validFrom(),
                row.validTo() == null ? null : row.validTo().minusDays(1), row.orgUnitId(), row.positionId(),
                row.jobTitleId(), days, consent, protection, r.retro(), r.earliestAffectedPeriod());
    }

    /** The request payload behind an applied version, when it came from a request. */
    private Optional<Map<String, Object>> proposalOf(AssignmentRow row) {
        return store.find(appliedIdOf(row.id())).filter(p -> p.employmentId().equals(row.employmentId()))
                .flatMap(store::proposalPayload);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private ChangeResult result(UUID proposalId, Applied a, UUID instanceId, List<Warning> warnings) {
        return new ChangeResult(proposalId, a.inForce() ? "APPLIED" : "SCHEDULED", instanceId,
                view(store.find(a.row().id()).orElseThrow()), a.decisionId(), a.decisionNo(), warnings);
    }

    private AssignmentRow currentVersion(UUID employmentId, UUID assignmentId) {
        AssignmentRow r = store.find(assignmentId).filter(x -> x.employmentId().equals(employmentId))
                .filter(x -> store.proposalPayload(x).isEmpty())
                .orElseThrow(() -> ApiException.notFound("Assignment", assignmentId));
        if (r.supersededAt() != null) {
            throw ApiException.conflict("assignment-superseded", "Assignment version " + assignmentId
                    + " has been superseded; act on the current version");
        }
        return r;
    }

    private void requireNoOtherTransfer(UUID employmentId, LocalDate from, LocalDate toExclusive) {
        boolean overlap = store.current(employmentId).stream()
                .filter(r -> "TEMP_TRANSFER".equals(r.assignmentType()))
                .anyMatch(r -> r.validFrom().isBefore(toExclusive)
                        && (r.validTo() == null || r.validTo().isAfter(from)));
        if (overlap) {
            throw ApiException.unprocessable("temp-transfer-overlap",
                    "Another temporary transfer of this employment overlaps the period");
        }
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

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason-required", "A reason is required");
        }
        if (reason.startsWith(AssignmentStore.PROPOSAL_PREFIX.trim())) {
            throw ApiException.unprocessable("invalid-reason", "The reason may not start with PROPOSAL");
        }
    }

    private static AssignmentRow newRow(UUID id, UUID key, Employment emp, String type, Attributes a, String action,
            String reasonCode, UUID decisionId, LocalDate from, LocalDate to, String source) {
        return new AssignmentRow(id, key, emp.id(), type, emp.legalEntityId(), emp.legalEntityId(), a.positionId(),
                a.orgUnitId(), a.jobTitleId(), a.gradeId(), a.managerEmploymentId(), a.locationId(), a.costCentreId(),
                a.costSplit(), a.fte() == null ? BigDecimal.ONE : a.fte(), action, reasonCode, decisionId, from, to,
                source, null, null, null, null);
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v instanceof UUID u ? u.toString() : v instanceof LocalDate d ? d.toString() : v);
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

    AssignmentView view(AssignmentRow r) {
        return views(List.of(r)).getFirst();
    }

    private List<AssignmentView> views(List<AssignmentRow> rows) {
        Map<UUID, String> numbers = decisionNumbers(rows.stream().map(AssignmentRow::decisionId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        List<AssignmentView> out = new ArrayList<>();
        for (AssignmentRow r : rows) {
            out.add(new AssignmentView(r.id(), r.assignmentKey(), r.employmentId(), r.assignmentType(),
                    r.legalEntityId(), r.payingLegalEntityId(), r.orgUnitId(), r.positionId(), r.jobTitleId(),
                    r.gradeId(), r.managerEmploymentId(), r.locationId(), r.costCentreId(), r.costSplit(), r.fte(),
                    r.actionCode(), r.reasonCode(), r.decisionId(), numbers.get(r.decisionId()), r.validFrom(),
                    r.validTo(), r.recordedAt(), r.supersededAt(), r.supersededBy(), r.correctionReason(),
                    r.source()));
        }
        return out;
    }

    private Map<UUID, String> decisionNumbers(Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        db.jdbc().sql("select id, decision_no from hr.decision where tenant_id = :t and id in (:ids)")
                .param("t", TenantContext.current().tenantId()).param("ids", Set.copyOf(ids))
                .query((rs, n) -> out.put(rs.getObject(1, UUID.class), rs.getString(2))).list();
        return out;
    }
}
