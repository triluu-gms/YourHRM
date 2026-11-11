package vn.yourhrm.people.internal.application.separation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Audience;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.people.api.ContractEvents.ProbationCompleted;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.SeparationAbsences;
import vn.yourhrm.people.api.SeparationAbsences.Absence;
import vn.yourhrm.people.api.SeparationEvents.TerminationCancelled;
import vn.yourhrm.people.api.SeparationEvents.TerminationScheduled;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.assignment.EmploymentFacts;
import vn.yourhrm.people.internal.application.assignment.EmploymentFacts.Employment;
import vn.yourhrm.people.internal.application.assignment.RetroFlags;
import vn.yourhrm.people.internal.application.contract.ContractRecords;
import vn.yourhrm.people.internal.application.contract.ContractRecords.ContractView;
import vn.yourhrm.people.internal.application.family.SelfServiceScope;
import vn.yourhrm.people.internal.application.person.PersonContactService;
import vn.yourhrm.people.internal.application.person.ProtectedStatusQuery;
import vn.yourhrm.people.internal.application.separation.NoticeCalculator.Assessment;
import vn.yourhrm.people.internal.application.separation.SeparationReasons.GroundRule;
import vn.yourhrm.people.internal.application.separation.SeparationRecords.SeparationView;
import vn.yourhrm.people.internal.application.separation.SeparationRecords.ServicePeriodView;
import vn.yourhrm.people.internal.domain.person.PersonRules;
import vn.yourhrm.people.internal.domain.separation.LegalGround;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartResult;

/**
 * Separations (EP-PPL-08; BR-PPL-070..075; US-PPL-060..067): a record per employment (one live at a time) with the
 * legal ground and its statutory code, the dates (request, notice, last working day, termination, access revocation),
 * the notice required by law and its shortfall, the protected-employee check, the severance eligibility and the
 * service periods Payroll needs; approval through Core's workflow ({@code hr.SEPARATION} for HR, {@code hr.RESIGNATION}
 * for ESS), a numbered termination decision and {@code people.TerminationScheduled} on approval; the daily job
 * ({@link SeparationJobService}) terminates the employment on the day after the termination date.
 *
 * <p>Status flow: DRAFT → SUBMITTED → SCHEDULED (approved, decision issued; employment phase NOTICE) → COMPLETED; or
 * REJECTED (workflow), WITHDRAWN (by the employee before approval), CANCELLED (by HR, or an approved resignation
 * withdrawn with HR's consent → {@code people.TerminationCancelled}). The DDL status APPROVED is not used: approval
 * schedules the termination in the same transaction.
 */
@Service
public class SeparationService {

    private static final Logger log = LoggerFactory.getLogger(SeparationService.class);

    /**
     * HR data of a separation (PUT style). Dates default: notice = request = today, termination = access = LWD.
     * {@code specialTradeCode} / {@code noticeExemptReasonCode}: notice inputs, stored and re-used whenever the notice is
     * recomputed; {@code signatoryName} / {@code signatoryTitle}: signatory of the termination decision.
     */
    public record Attributes(String legalGroundCode, String reasonCode, LocalDate requestDate, LocalDate noticeDate,
            LocalDate lastWorkingDate, LocalDate terminationDate, LocalDate accessRevocationDate,
            String specialTradeCode, String noticeExemptReasonCode, String protectedOverrideReason,
            Boolean rehireEligible, String signatoryName, String signatoryTitle) {

        /** Without a decision signatory. */
        public Attributes(String legalGroundCode, String reasonCode, LocalDate requestDate, LocalDate noticeDate,
                LocalDate lastWorkingDate, LocalDate terminationDate, LocalDate accessRevocationDate,
                String specialTradeCode, String noticeExemptReasonCode, String protectedOverrideReason,
                Boolean rehireEligible) {
            this(legalGroundCode, reasonCode, requestDate, noticeDate, lastWorkingDate, terminationDate,
                    accessRevocationDate, specialTradeCode, noticeExemptReasonCode, protectedOverrideReason,
                    rehireEligible, null, null);
        }
    }

    /**
     * Protected-employee check (BR-PPL-073): special statuses on the notice and termination dates and T&amp;A's
     * protective absences in between. {@code leaveDataAvailable=false}: T&amp;A cannot answer (stub), HR must confirm
     * leave-based protection manually. {@code exceptionRequired}: an employer-initiated ground on a protected employee.
     */
    public record Protection(boolean protectedEmployee, List<String> statusReasons, List<Absence> absences,
            boolean leaveDataAvailable, boolean exceptionRequired, boolean exceptionDocumented) {
    }

    /** A separation with the assessment behind its stored figures. */
    public record SeparationResult(SeparationView separation, Assessment notice, Protection protection,
            String statutoryCode, List<String> warnings) {
    }

    /** The outcome of a preview (nothing stored). */
    public record Preview(String legalGroundCode, String separationType, String statutoryCode, Assessment notice,
            Protection protection, String severanceType, String severanceEligibility, LocalDate terminationDate,
            LocalDate accessRevocationDate, List<String> warnings) {
    }

    /**
     * Submission: OD-8 personal e-mail for the former-employee login, or HR's "no e-mail" declaration (stored in
     * {@code hr.separation.no_personal_email}). {@code note}: the employee's note of an ESS resignation is stored in
     * {@code employee_note}; HR's submission note only goes to the approvers.
     */
    public record Submission(String personalEmail, boolean noPersonalEmail, String note) {
    }

    public record ServicePeriodData(String periodKind, LocalDate periodFrom, LocalDate periodTo,
            UUID sourceEmploymentId, String note) {
    }

    /** ESS resignation (US-PPL-065). {@code employmentId} only when the employee has several employments. */
    public record Resignation(UUID employmentId, LocalDate proposedLastWorkingDate, String reasonCode, String note,
            String personalEmail) {
    }

    /** {@code outcome}: WITHDRAWN (before approval) or WITHDRAWAL_REQUESTED (HR consent pending) or CANCELLED. */
    public record WithdrawalResult(String outcome, SeparationView separation, UUID workflowInstanceId) {
    }

    private record Derived(LegalGround ground, GroundRule rule, String reasonCode, LocalDate requestDate,
            LocalDate noticeDate, LocalDate lastWorkingDate, LocalDate terminationDate, LocalDate accessRevocationDate,
            Assessment notice, Protection protection, String severanceEligibility, String overrideReason,
            Boolean rehireEligible, String specialTradeCode, String exemptReasonCode, String signatoryName,
            String signatoryTitle, List<String> warnings) {
    }

    private final HrDb db;
    private final SeparationRecords records;
    private final SeparationReasons reasons;
    private final NoticeCalculator notice;
    private final EmploymentFacts employments;
    private final ContractRecords contracts;
    private final EmployeeAccess access;
    private final ProtectedStatusQuery protectedStatus;
    private final SeparationAbsences absences;
    private final PersonContactService contacts;
    private final SelfServiceScope self;
    private final DecisionService decisions;
    private final WorkflowService workflow;
    private final Documents documents;
    private final Notifications notifications;
    private final HrOutbox outbox;
    private final RetroFlags retro;
    private final AuditLog audit;
    private final BusinessClock clock;

    public SeparationService(HrDb db, SeparationRecords records, SeparationReasons reasons, NoticeCalculator notice,
            EmploymentFacts employments, ContractRecords contracts, EmployeeAccess access,
            ProtectedStatusQuery protectedStatus, ObjectProvider<SeparationAbsences> absences,
            PersonContactService contacts, SelfServiceScope self, DecisionService decisions, WorkflowService workflow,
            Documents documents, Notifications notifications, HrOutbox outbox, RetroFlags retro, AuditLog audit,
            BusinessClock clock) {
        this.db = db;
        this.records = records;
        this.reasons = reasons;
        this.notice = notice;
        this.employments = employments;
        this.contracts = contracts;
        this.access = access;
        this.protectedStatus = protectedStatus;
        // T&A implements the absence SPI; until then leave data is "unavailable" (documented stub)
        this.absences = absences.getIfAvailable(() -> SeparationAbsences.NONE);
        this.contacts = contacts;
        this.self = self;
        this.decisions = decisions;
        this.workflow = workflow;
        this.documents = documents;
        this.notifications = notifications;
        this.outbox = outbox;
        this.retro = retro;
        this.audit = audit;
        this.clock = clock;
    }

    // ================================================================================================== queries

    @HrTransactional(readOnly = true)
    public SeparationView get(UUID id) {
        SeparationView s = records.require(id);
        requireScope(PeoplePermissions.SEPARATION_VIEW, s);
        return s;
    }

    @HrTransactional(readOnly = true)
    public List<SeparationView> ofEmployment(UUID employmentId) {
        access.require(PeoplePermissions.SEPARATION_VIEW, employmentId, clock.today());
        return records.ofEmployment(employmentId);
    }

    /** The live separation of an employment (404 when there is none). */
    @HrTransactional(readOnly = true)
    public SeparationView liveOf(UUID employmentId) {
        access.require(PeoplePermissions.SEPARATION_VIEW, employmentId, clock.today());
        return records.live(employmentId).orElseThrow(() -> ApiException.notFound("Separation of employment",
                employmentId));
    }

    /** Separations in the caller's scope, optionally by status / legal entity / termination-date range. */
    @HrTransactional(readOnly = true)
    public List<SeparationView> list(String status, UUID legalEntityId, LocalDate from, LocalDate to) {
        LocalDate today = clock.today();
        var scope = access.scope(PeoplePermissions.SEPARATION_VIEW, today, "s");
        List<UUID> ids = db.jdbc().sql("select s.id from " + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN + """
                         join hr.separation s on s.tenant_id = e.tenant_id and s.employment_id = e.id
                        where e.tenant_id = :t
                          and (cast(:st as text) is null or s.status = :st)
                          and (cast(:le as uuid) is null or e.legal_entity_id = :le)
                          and (cast(:from as date) is null or s.termination_date >= :from)
                          and (cast(:to as date) is null or s.termination_date <= :to)
                          and""" + " " + scope.sql() + " order by s.termination_date nulls last, s.created_at")
                .param("t", tenant()).param("asOf", today).param("st", status).param("le", legalEntityId)
                .param("from", from).param("to", to).params(scope.params())
                .query(UUID.class).list();
        return ids.stream().map(records::require).toList();
    }

    /** US-PPL-060: the legal grounds with their statutory codes and the tenant's sub-reasons. */
    @HrTransactional(readOnly = true)
    public SeparationReasons.Catalogue reasons(UUID legalEntityId, LocalDate asOf) {
        return reasons.catalogue(legalEntityId, asOf == null ? clock.today() : asOf);
    }

    /** Notice, protection and severance for a planned separation, without storing anything (US-PPL-062/063). */
    @HrTransactional(readOnly = true)
    public Preview preview(UUID employmentId, Attributes a) {
        access.require(PeoplePermissions.SEPARATION_CREATE, employmentId, clock.today());
        Employment emp = liveEmployment(employmentId);
        Derived d = derive(emp, a, false);
        return new Preview(d.ground().code(), d.ground().separationType(), d.rule().statutoryCode(), d.notice(),
                d.protection(), d.rule().severanceType(), d.severanceEligibility(), d.terminationDate(),
                d.accessRevocationDate(), d.warnings());
    }

    // ================================================================================================== HR commands

    /** A new DRAFT separation (US-PPL-061). */
    @HrTransactional
    public SeparationResult create(UUID employmentId, Attributes a) {
        access.require(PeoplePermissions.SEPARATION_CREATE, employmentId, clock.today());
        return createDraft(liveEmployment(employmentId), a);
    }

    /** Replaces the data of a DRAFT separation. */
    @HrTransactional
    public SeparationResult update(UUID id, int expectedVersion, Attributes a) {
        SeparationView s = records.lock(id);
        requireScope(PeoplePermissions.SEPARATION_UPDATE, s);
        requireStatus(s, "DRAFT");
        requireVersion(s, expectedVersion);
        Employment emp = liveEmployment(s.employmentId());
        Derived d = derive(emp, a, true);
        db.jdbc().sql("""
                        update hr.separation set separation_type = :type, initiated_by = :init, reason_code = :reason,
                               legal_ground_code = :ground, request_date = :req, notice_date = :nd,
                               required_notice_days = :rnd, notice_day_basis = :basis, notice_shortfall_days = :short,
                               last_working_date = :lwd, termination_date = :td, access_revocation_date = :ard,
                               severance_type = :sev, severance_eligibility = :elig, is_protected = :prot,
                               protected_override_reason = :ovr, is_rehire_eligible = :rehire,
                               special_trade_code = :trade, notice_exempt_reason_code = :exempt,
                               signatory_name = :sn, signatory_title = :st,
                               version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .params(columns(d)).param("a", actor()).param("t", tenant()).param("id", id).update();
        SeparationView updated = records.require(id);
        records.refreshDerivedPeriods(updated);
        audit("UPDATE_SEPARATION", updated, Map.of("legalGroundCode", d.ground().code()));
        return result(records.require(id), d);
    }

    /** Uploads the evidence of a lawful exception for a protected employee (US-PPL-063). */
    @HrTransactional
    public SeparationView uploadEvidence(UUID id, String title, String fileName, String mediaType, byte[] content) {
        SeparationView s = records.lock(id);
        requireScope(PeoplePermissions.SEPARATION_UPDATE, s);
        if (!List.of("DRAFT").contains(s.status())) {
            throw ApiException.unprocessable("invalid-status-transition",
                    "Evidence can be attached while the separation is a draft; it is " + s.status());
        }
        Documents.DocumentView doc = documents.upload(new Documents.Upload(SeparationKeys.EVIDENCE_DOC, id,
                s.legalEntityId(), s.personId(), title == null ? "Căn cứ ngoại lệ chấm dứt " + s.employeeNo() : title,
                "CONFIDENTIAL", fileName, mediaType, content));
        db.jdbc().sql("""
                        update hr.separation set protected_override_document_id = :doc, version = version + 1,
                               updated_by = :a where tenant_id = :t and id = :id""")
                .param("doc", doc.id()).param("a", actor()).param("t", tenant()).param("id", id).update();
        audit("ATTACH_SEPARATION_EVIDENCE", s, Map.of("documentId", doc.id().toString()));
        return records.require(id);
    }

    /** Submits a DRAFT for approval ({@code hr.SEPARATION}); applies it at once when every step was skipped. */
    @HrTransactional
    public SeparationResult submit(UUID id, int expectedVersion, Submission sub) {
        SeparationView s = records.lock(id);
        requireScope(PeoplePermissions.SEPARATION_UPDATE, s);
        requireStatus(s, "DRAFT");
        requireVersion(s, expectedVersion);
        return submitInternal(s, sub, SeparationKeys.PROCESS_SEPARATION, "HR");
    }

    /**
     * HR cancels a separation: a draft or a pending request is closed (CANCELLED, no event); an approved one is
     * cancelled with its decision and, if the employment was already terminated, the employment is reinstated
     * ({@code people.TerminationCancelled}, US-PPL-065 "after approval only with HR consent").
     */
    @HrTransactional
    public SeparationView cancel(UUID id, int expectedVersion, String reason) {
        SeparationView s = records.lock(id);
        requireScope(PeoplePermissions.SEPARATION_UPDATE, s);
        requireVersion(s, expectedVersion);
        switch (s.status()) {
            case "DRAFT", "SUBMITTED" -> {
                setStatus(s, "CANCELLED");
                audit("CANCEL_SEPARATION", s, Map.of("previousStatus", s.status(), "reason", nz(reason)));
            }
            case "APPROVED", "SCHEDULED", "COMPLETED" -> cancelApproved(s, reason == null ? "Cancelled by HR" : reason);
            default -> throw ApiException.unprocessable("invalid-status-transition", "Separation is " + s.status());
        }
        return records.require(id);
    }

    @HrTransactional
    public ServicePeriodView addServicePeriod(UUID separationId, ServicePeriodData p) {
        SeparationView s = records.lock(separationId);
        requireScope(PeoplePermissions.SEPARATION_UPDATE, s);
        if (!List.of("DRAFT", "SUBMITTED", "SCHEDULED").contains(s.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Service periods can be changed until the "
                    + "employment is terminated; the separation is " + s.status());
        }
        UUID pid = insertPeriod(s, p);
        audit("ADD_SERVICE_PERIOD", s, Map.of("periodKind", p.periodKind()));
        return records.periods(separationId).stream().filter(x -> x.id().equals(pid)).findFirst().orElseThrow();
    }

    /**
     * Service periods from the opening-balance import (US-PPL-064 "incl. imported history", ADR-0037): no permission
     * check, the import flow authorises. Joins the caller's People transaction.
     */
    @HrTransactional
    public List<ServicePeriodView> addServicePeriodsForInitialLoad(UUID separationId, List<ServicePeriodData> periods) {
        SeparationView s = records.lock(separationId);
        periods.forEach(p -> insertPeriod(s, p));
        return records.periods(separationId);
    }

    @HrTransactional
    public void removeServicePeriod(UUID separationId, UUID periodId) {
        SeparationView s = records.lock(separationId);
        requireScope(PeoplePermissions.SEPARATION_UPDATE, s);
        ServicePeriodView p = s.servicePeriods().stream().filter(x -> x.id().equals(periodId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Service period", periodId));
        if (p.derived()) {
            throw ApiException.unprocessable("derived-service-period", "People derives this period from the "
                    + "employment history; add an EXCLUDED period instead");
        }
        if ("COMPLETED".equals(s.status()) || !s.live()) {
            throw ApiException.unprocessable("invalid-status-transition", "Separation is " + s.status());
        }
        db.jdbc().sql("delete from hr.separation_service_period where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", periodId).update();
        audit("REMOVE_SERVICE_PERIOD", s, Map.of("periodKind", p.periodKind()));
    }

    // ================================================================================================== hooks

    /**
     * Opens a DRAFT separation for a contract ending at expiry ("terminate at expiry" from the expiry list,
     * US-PPL-052): ground CONTRACT_EXPIRY, last working day = contract end date. Checks {@code hr.separation.create}.
     */
    @HrTransactional
    public SeparationResult openForContractExpiry(UUID contractId, LocalDate noticeDate) {
        ContractView c = contracts.require(contractId);
        access.require(PeoplePermissions.SEPARATION_CREATE, c.employmentId(), clock.today());
        if (c.endDate() == null) {
            throw ApiException.unprocessable("contract-without-end-date", "Contract " + c.contractNo()
                    + " has no end date; use another legal ground");
        }
        if (!ContractRecords.LIVE.contains(c.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Contract " + c.contractNo() + " is "
                    + c.status());
        }
        LocalDate nd = noticeDate == null ? clock.today() : noticeDate;
        if (nd.isAfter(c.endDate())) {
            nd = c.endDate();
        }
        return createDraft(liveEmployment(c.employmentId()), new Attributes(LegalGround.CONTRACT_EXPIRY.code(), null,
                nd, nd, c.endDate(), c.endDate(), null, null, null, null, null));
    }

    /**
     * Opens a DRAFT separation (ground PROBATION_FAILED) when a probation is not passed (US-PPL-049/061); in-process
     * hook of {@code people.ProbationCompleted} FAIL. No permission check (system flow). Skips when the employment
     * already has a live separation.
     */
    @HrTransactional
    public Optional<SeparationResult> openForProbationFailure(ProbationCompleted e) {
        if (records.live(e.employmentId()).isPresent()) {
            return Optional.empty();
        }
        Employment emp = employments.find(e.employmentId()).filter(Employment::live).orElse(null);
        if (emp == null) {
            return Optional.empty();
        }
        LocalDate result = e.resultDate() == null ? clock.today() : e.resultDate();
        LocalDate lwd = e.probationEndDate() != null && !e.probationEndDate().isBefore(result)
                ? e.probationEndDate() : result;
        SeparationResult r = createDraft(emp, new Attributes(LegalGround.PROBATION_FAILED.code(), null, result, result,
                lwd, lwd, null, null, null, null, null));
        notifyHr(SeparationKeys.EVENT_DRAFT_OPENED, r.separation(), Map.of("employeeNo", emp.employeeNo(),
                "groundVi", LegalGround.PROBATION_FAILED.nameVi(), "groundEn", LegalGround.PROBATION_FAILED.nameEn(),
                "lastWorkingDate", lwd.toString()), "separation-draft:" + r.separation().id());
        return Optional.of(r);
    }

    // ================================================================================================== ESS

    /** US-PPL-065: the employee resigns (proposed LWD, reason, note); line manager then HR approve. */
    @HrTransactional
    public SeparationResult resign(Resignation r) {
        SelfServiceScope.Self me = self.require(PeoplePermissions.ESS_REQUEST);
        UUID employmentId = ownEmployment(me, r.employmentId());
        Employment emp = liveEmployment(employmentId);
        if (r.proposedLastWorkingDate() == null) {
            throw ApiException.unprocessable("last-working-date-required", "Propose a last working day");
        }
        LocalDate today = clock.today();
        if (r.proposedLastWorkingDate().isBefore(today)) {
            throw ApiException.unprocessable("invalid-last-working-date", "The last working day cannot be in the past");
        }
        if (r.personalEmail() == null && contacts.primaryPersonalEmail(emp.personId()).isEmpty()) {
            throw ApiException.unprocessable("personal-email-required", "Give a personal e-mail address: it is your "
                    + "sign-in for former employees (contracts, decisions, certificates)");
        }
        SeparationResult draft = createDraft(emp, new Attributes(LegalGround.EMPLOYEE_RESIGNATION.code(),
                r.reasonCode(), today, today, r.proposedLastWorkingDate(), r.proposedLastWorkingDate(), null, null,
                null, null, null));
        SeparationView s = records.lock(draft.separation().id());
        return submitInternal(s, new Submission(r.personalEmail(), false, r.note()), SeparationKeys.PROCESS_RESIGNATION,
                "ESS");
    }

    /** The employee's live (or latest) separation, if any. */
    @HrTransactional(readOnly = true)
    public Optional<SeparationView> myResignation() {
        SelfServiceScope.Self me = self.require(PeoplePermissions.ESS_VIEW);
        return me.employmentIds().stream().flatMap(e -> records.ofEmployment(e).stream())
                .filter(SeparationView::live).findFirst()
                .or(() -> me.employmentIds().stream().flatMap(e -> records.ofEmployment(e).stream()).findFirst());
    }

    /**
     * US-PPL-065: before approval the employee withdraws freely (WITHDRAWN); after approval the withdrawal needs HR's
     * consent ({@code hr.RESIGNATION_WITHDRAWAL}; on approval the separation is cancelled with
     * {@code people.TerminationCancelled}).
     */
    @HrTransactional
    public WithdrawalResult withdrawOwn(UUID id, String note) {
        SelfServiceScope.Self me = self.require(PeoplePermissions.ESS_REQUEST);
        SeparationView s = records.lock(id);
        if (!me.employmentIds().contains(s.employmentId()) || !"EMPLOYEE".equals(s.initiatedBy())) {
            throw ApiException.notFound("Resignation", id);
        }
        switch (s.status()) {
            case "DRAFT", "SUBMITTED" -> {
                db.jdbc().sql("""
                                update hr.separation set status = 'WITHDRAWN', withdrawn_at = now(), version = version + 1,
                                       updated_by = :a where tenant_id = :t and id = :id""")
                        .param("a", actor()).param("t", tenant()).param("id", id).update();
                audit("WITHDRAW_RESIGNATION", s, Map.of("previousStatus", s.status()));
                return new WithdrawalResult("WITHDRAWN", records.require(id), s.workflowInstanceId());
            }
            case "APPROVED", "SCHEDULED" -> {
                AuthorizationTarget.Employee target = target(s.employmentId());
                Map<String, Object> summary = summary(s);
                if (note != null) {
                    summary.put("note", note);
                }
                StartResult started = workflow.start(new StartRequest(SeparationKeys.PROCESS_RESIGNATION_WITHDRAWAL,
                        SeparationKeys.SUBJECT_TYPE, id, s.version(), "hr.separation-withdrawal:" + id + ":" + s.version(),
                        null, s.personId(), s.legalEntityId(), target.orgUnitId(), target.locationId(), null, summary,
                        Map.of(), Map.of()));
                audit("REQUEST_RESIGNATION_WITHDRAWAL", s, Map.of("workflowInstanceId", started.instanceId().toString()));
                if ("APPROVED".equals(started.status())) {
                    cancelApproved(s, "Withdrawal of the resignation accepted");
                    return new WithdrawalResult("CANCELLED", records.require(id), started.instanceId());
                }
                return new WithdrawalResult("WITHDRAWAL_REQUESTED", s, started.instanceId());
            }
            default -> throw ApiException.unprocessable("invalid-status-transition",
                    "The resignation is " + s.status() + " and can no longer be withdrawn");
        }
    }

    // ================================================================================================== workflow

    /**
     * Applies the outcome of {@code hr.SEPARATION} / {@code hr.RESIGNATION}: APPROVED schedules the termination
     * (decision, {@code people.TerminationScheduled}); REJECTED / WITHDRAWN / CANCELLED close the request. Idempotent:
     * a separation no longer SUBMITTED is left alone.
     *
     * @return the new status, empty when nothing changed
     */
    @HrTransactional
    public Optional<String> applyOutcome(UUID separationId, UUID workflowInstanceId, String outcome) {
        SeparationView s = records.lock(separationId);
        if (!"SUBMITTED".equals(s.status())) {
            return Optional.empty();
        }
        switch (outcome) {
            case "APPROVED" -> {
                schedule(s, workflowInstanceId);
                return Optional.of("SCHEDULED");
            }
            case "REJECTED" -> {
                setStatus(s, "REJECTED");
                audit("REJECT_SEPARATION", s, Map.of());
                return Optional.of("REJECTED");
            }
            default -> {
                db.jdbc().sql("""
                                update hr.separation set status = 'WITHDRAWN', withdrawn_at = now(), version = version + 1
                                 where tenant_id = :t and id = :id""")
                        .param("t", tenant()).param("id", separationId).update();
                return Optional.of("WITHDRAWN");
            }
        }
    }

    /** Applies the outcome of {@code hr.RESIGNATION_WITHDRAWAL}: APPROVED cancels the approved separation. */
    @HrTransactional
    public Optional<String> applyWithdrawalOutcome(UUID separationId, String outcome) {
        SeparationView s = records.lock(separationId);
        if (!"APPROVED".equals(outcome) || !List.of("APPROVED", "SCHEDULED").contains(s.status())) {
            return Optional.empty();
        }
        cancelApproved(s, "Withdrawal of the resignation accepted by HR");
        return Optional.of("CANCELLED");
    }

    // ================================================================================================== internals

    private SeparationResult createDraft(Employment emp, Attributes a) {
        if (records.live(emp.id()).isPresent()) {
            throw ApiException.conflict("separation-exists", "Employment " + emp.employeeNo()
                    + " already has a separation in progress");
        }
        Derived d = derive(emp, a, true);
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into hr.separation (tenant_id, id, employment_id, separation_type, initiated_by,
                               reason_code, legal_ground_code, request_date, notice_date, required_notice_days,
                               notice_day_basis, notice_shortfall_days, last_working_date, termination_date,
                               access_revocation_date, severance_type, severance_eligibility, is_protected,
                               protected_override_reason, is_rehire_eligible, special_trade_code,
                               notice_exempt_reason_code, signatory_name, signatory_title, status, created_by,
                               updated_by)
                        values (:t, :id, :emp, :type, :init, :reason, :ground, :req, :nd, :rnd, :basis, :short, :lwd,
                                :td, :ard, :sev, :elig, :prot, :ovr, :rehire, :trade, :exempt, :sn, :st, 'DRAFT',
                                :a, :a)""")
                .params(columns(d)).param("t", tenant()).param("id", id).param("emp", emp.id()).param("a", actor())
                .update();
        SeparationView s = records.require(id);
        records.refreshDerivedPeriods(s);
        audit("CREATE_SEPARATION", s, Map.of("legalGroundCode", d.ground().code()));
        return result(records.require(id), d);
    }

    private SeparationResult submitInternal(SeparationView s, Submission sub, String processType, String channel) {
        Employment emp = liveEmployment(s.employmentId());
        // re-derive: statuses, leave and legal parameters may have changed since the draft
        Derived d = derive(emp, attributesOf(s), true);
        if (d.protection().exceptionRequired() && s.protectedOverrideDocumentId() == null) {
            throw ApiException.unprocessable("exception-evidence-required", "Employee " + emp.employeeNo()
                    + " is protected (" + String.join(", ", d.protection().statusReasons()) + "): attach the evidence "
                    + "of the lawful exception before submitting", Map.of("reasons", d.protection().statusReasons()));
        }
        boolean emailOnFile = personalEmail(emp, sub);
        boolean ess = "ESS".equals(channel);
        db.jdbc().sql("""
                        update hr.separation set required_notice_days = :rnd, notice_day_basis = :basis,
                               notice_shortfall_days = :short, severance_type = :sev, severance_eligibility = :elig,
                               is_protected = :prot, no_personal_email = :noEmail,
                               employee_note = case when :ess then cast(:note as text) else employee_note end,
                               status = 'SUBMITTED', version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .params(columns(d)).param("noEmail", !emailOnFile).param("ess", ess)
                .param("note", ess ? blank(sub.note()) : null)
                .param("a", actor()).param("t", tenant()).param("id", s.id()).update();
        SeparationView submitted = records.require(s.id());
        AuthorizationTarget.Employee target = target(s.employmentId());
        Map<String, Object> summary = summary(submitted);
        summary.put("channel", channel);
        summary.put("formerEmployeeEmailOnFile", emailOnFile);
        if (sub.note() != null) {
            summary.put("note", sub.note());
        }
        Map<String, Object> conditions = new HashMap<>();
        conditions.put("separationType", submitted.separationType());
        conditions.put("initiatedBy", submitted.initiatedBy());
        conditions.put("protectedOverride", d.protection().exceptionRequired());
        conditions.put("noticeShortfall", d.notice().shortfallDays() > 0);
        StartResult started = workflow.start(new StartRequest(processType, SeparationKeys.SUBJECT_TYPE, s.id(),
                submitted.version(), processType + ":" + s.id(), null, s.personId(), s.legalEntityId(),
                target.orgUnitId(), target.locationId(), null, summary, conditions, Map.of()));
        db.jdbc().sql("update hr.separation set workflow_instance_id = :wf where tenant_id = :t and id = :id")
                .param("wf", started.instanceId()).param("t", tenant()).param("id", s.id()).update();
        audit("SUBMIT_SEPARATION", submitted, Map.of("workflowInstanceId", started.instanceId().toString(),
                "channel", channel, "noPersonalEmail", String.valueOf(!emailOnFile)));
        if ("APPROVED".equals(started.status())) {
            applyOutcome(s.id(), started.instanceId(), "APPROVED");
        }
        return result(records.require(s.id()), d);
    }

    /** Approval: the numbered termination decision, SCHEDULED and {@code people.TerminationScheduled}. */
    private void schedule(SeparationView s, UUID workflowInstanceId) {
        Employment emp = liveEmployment(s.employmentId());
        LegalGround ground = reasons.requireGround(s.legalGroundCode());
        GroundRule rule = reasons.rule(ground, s.legalEntityId(), s.noticeDate());
        boolean emailOnFile = requireEmailOrDeclared(s);
        var decision = decisions.issue(new DecisionService.Issue(s.legalEntityId(), ground.decisionType(), emp.id(),
                s.terminationDate(), s.signatoryName(), s.signatoryTitle(), workflowInstanceId));
        db.jdbc().sql("""
                        update hr.separation set status = 'SCHEDULED', decision_id = :d, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("d", decision.id()).param("t", tenant()).param("id", s.id()).update();
        SeparationView scheduled = records.require(s.id());
        records.refreshDerivedPeriods(scheduled);
        List<String> consequences = List.of();
        if (scheduled.noticeShortfallDays() != null && scheduled.noticeShortfallDays() > 0) {
            consequences = consequences(scheduled, rule);
        }
        outbox.publish(new TerminationScheduled(tenant(), s.legalEntityId(), s.personId(), s.employmentId(),
                s.terminationDate(), s.id(), decision.id(), decision.decisionNo(), s.separationType(),
                s.initiatedBy(), s.legalGroundCode(), s.reasonCode(), rule.statutoryCode(), s.noticeDate(),
                s.lastWorkingDate(), s.terminationDate(), s.requiredNoticeDays(), s.noticeDayBasis(),
                s.noticeShortfallDays(), consequences, s.protectedEmployee(), s.accessRevocationDate(),
                SeparationRecords.revocationAt(s.accessRevocationDate()) == null ? null
                        : SeparationRecords.revocationAt(s.accessRevocationDate()).toInstant(),
                s.severanceType(), s.severanceEligibility(), emailOnFile,
                retro.of(s.legalEntityId(), s.terminationDate()).retro()));
        audit("APPROVE_SEPARATION", scheduled, Map.of("decisionNo", decision.decisionNo()));
    }

    private List<String> consequences(SeparationView s, GroundRule rule) {
        try {
            Assessment a = notice.assess(new NoticeCalculator.Input(rule.noticeSide(), s.employmentId(),
                    s.legalEntityId(), target(s.employmentId()).locationId(), s.noticeDate(), s.terminationDate(),
                    s.specialTradeCode(), s.noticeExemptReasonCode()));
            return a.consequences();
        } catch (ApiException e) {
            return List.of();
        }
    }

    /** Cancels an approved / scheduled / completed separation with its decision; reinstates a terminated employment. */
    void cancelApproved(SeparationView s, String reason) {
        boolean reinstated = false;
        if ("COMPLETED".equals(s.status())) {
            reinstated = db.jdbc().sql("""
                            update hr.employment set status = 'ACTIVE', end_date = null, last_working_date = null,
                                   version = version + 1, updated_by = :a
                             where tenant_id = :t and id = :e and status = 'TERMINATED'
                               and end_date is not distinct from :end""")
                    .param("a", actor()).param("t", tenant()).param("e", s.employmentId())
                    .param("end", s.terminationDate()).update() > 0;
        }
        if (s.decisionId() != null) {
            decisions.find(s.decisionId()).filter(dc -> !"CANCELLED".equals(dc.status()))
                    .ifPresent(dc -> decisions.cancel(dc.id(), dc.version()));
        }
        setStatus(s, "CANCELLED");
        outbox.publish(new TerminationCancelled(tenant(), s.legalEntityId(), s.personId(), s.employmentId(),
                s.terminationDate(), s.id(), s.decisionId(), s.status(), reinstated, reason));
        audit("CANCEL_SEPARATION", s, Map.of("previousStatus", s.status(), "reason", nz(reason),
                "employmentReinstated", String.valueOf(reinstated)));
    }

    private Derived derive(Employment emp, Attributes a, boolean strict) {
        LocalDate today = clock.today();
        List<String> warnings = new ArrayList<>();
        if (a.legalGroundCode() == null || a.legalGroundCode().isBlank()) {
            throw ApiException.unprocessable("legal-ground-required", "Choose the legal ground of the separation");
        }
        LegalGround ground = reasons.requireGround(a.legalGroundCode());
        if (a.lastWorkingDate() == null) {
            throw ApiException.unprocessable("last-working-date-required", "Give the last working day");
        }
        LocalDate requestDate = a.requestDate() == null ? today : a.requestDate();
        LocalDate noticeDate = a.noticeDate() == null ? requestDate : a.noticeDate();
        LocalDate lwd = a.lastWorkingDate();
        LocalDate td = a.terminationDate() == null ? lwd : a.terminationDate();
        LocalDate ard = a.accessRevocationDate() == null ? lwd : a.accessRevocationDate();
        if (lwd.isBefore(emp.hireDate())) {
            throw ApiException.unprocessable("invalid-last-working-date", "The last working day is before the hire "
                    + "date " + emp.hireDate());
        }
        if (td.isBefore(lwd)) {
            throw ApiException.unprocessable("invalid-termination-date",
                    "The termination date cannot be before the last working day");
        }
        if (lwd.isBefore(noticeDate)) {
            throw ApiException.unprocessable("invalid-notice-date", "The notice date is after the last working day");
        }
        GroundRule rule = reasons.rule(ground, emp.legalEntityId(), noticeDate);
        String reasonCode = reasons.requireSubReason(a.reasonCode(), ground, noticeDate);
        AuthorizationTarget.Employee target = target(emp.id());
        Assessment n = notice.assess(new NoticeCalculator.Input(rule.noticeSide(), emp.id(), emp.legalEntityId(),
                target.locationId(), noticeDate, td, blank(a.specialTradeCode()), blank(a.noticeExemptReasonCode())));
        if (n.shortfallDays() > 0) {
            warnings.add("notice-shortfall: " + n.shortfallDays() + " " + n.basis().toLowerCase() + " day(s) short of "
                    + n.requiredDays() + "; consequences " + n.consequences());
        }
        Protection protection = protection(emp, ground, noticeDate, td, blank(a.protectedOverrideReason()));
        if (!protection.leaveDataAvailable() && ground.employerInitiated()) {
            warnings.add("leave-data-unavailable: maternity / sick-leave protection could not be checked "
                    + "(Time & Attendance not available); confirm it before approving");
        }
        if (protection.exceptionRequired() && blank(a.protectedOverrideReason()) == null) {
            throw ApiException.unprocessable("protected-employee", "Employee " + emp.employeeNo() + " is protected "
                    + "against employer-initiated termination (" + String.join(", ", protectionReasons(protection))
                    + "); document the lawful exception (legal basis, evidence, HR manager approval) or choose "
                    + "another ground", Map.of("reasons", protectionReasons(protection)));
        }
        String overrideReason = protection.exceptionRequired() ? blank(a.protectedOverrideReason()) : null;
        if (!protection.exceptionRequired() && blank(a.protectedOverrideReason()) != null) {
            warnings.add("protected-override-ignored: no exception is needed for this ground / employee");
        }
        String eligibility = "NONE".equals(rule.severanceType()) || n.forfeitsSeverance() ? "NOT_ELIGIBLE" : "ELIGIBLE";
        if (strict && "PRE_HIRE".equals(emp.status())) {
            throw ApiException.unprocessable("employment-not-started", "Employment " + emp.employeeNo()
                    + " has not started; cancel the hire instead");
        }
        return new Derived(ground, rule, reasonCode, requestDate, noticeDate, lwd, td, ard, n, protection, eligibility,
                overrideReason, a.rehireEligible(), blank(a.specialTradeCode()), blank(a.noticeExemptReasonCode()),
                blank(a.signatoryName()), blank(a.signatoryTitle()), List.copyOf(warnings));
    }

    private Protection protection(Employment emp, LegalGround ground, LocalDate from, LocalDate to, String override) {
        Set<String> reasonsSet = new LinkedHashSet<>(protectedStatus.protectedReasons(emp.personId(), emp.id(), from));
        reasonsSet.addAll(protectedStatus.protectedReasons(emp.personId(), emp.id(), to));
        SeparationAbsences.Result leave = absences.protectiveAbsences(emp.id(), from, to);
        boolean isProtected = !reasonsSet.isEmpty() || !leave.absences().isEmpty();
        boolean required = isProtected && ground.employerInitiated();
        // pregnancy / health are sensitive (P3-derived): only holders of the special-status view see the type
        boolean reveal = access.can(PeoplePermissions.SPECIAL_STATUS_VIEW, emp.id(), clock.today());
        List<String> shown = reveal ? List.copyOf(reasonsSet) : reasonsSet.stream().map(r -> "SPECIAL_STATUS")
                .distinct().toList();
        List<Absence> leaves = reveal ? leave.absences() : leave.absences().stream()
                .map(a -> new Absence("PROTECTED_LEAVE", a.from(), a.to())).toList();
        return new Protection(isProtected, shown, leaves, leave.available(), required, required && override != null);
    }

    private static List<String> protectionReasons(Protection p) {
        List<String> out = new ArrayList<>(p.statusReasons());
        p.absences().forEach(x -> out.add(x.absenceTypeCode()));
        return out;
    }

    private static Map<String, Object> columns(Derived d) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", d.ground().separationType());
        m.put("init", d.ground().initiatedBy());
        m.put("reason", d.reasonCode());
        m.put("ground", d.ground().code());
        m.put("req", d.requestDate());
        m.put("nd", d.noticeDate());
        m.put("rnd", d.notice().requiredDays());
        m.put("basis", d.notice().basis());
        m.put("short", d.notice().requiredDays() == null ? null : d.notice().shortfallDays());
        m.put("lwd", d.lastWorkingDate());
        m.put("td", d.terminationDate());
        m.put("ard", d.accessRevocationDate());
        m.put("sev", d.rule().severanceType());
        m.put("elig", d.severanceEligibility());
        m.put("prot", d.protection().protectedEmployee());
        m.put("ovr", d.overrideReason());
        m.put("rehire", d.rehireEligible());
        m.put("trade", d.specialTradeCode());
        m.put("exempt", d.exemptReasonCode());
        m.put("sn", d.signatoryName());
        m.put("st", d.signatoryTitle());
        return m;
    }

    /** The stored attributes of a draft, notice inputs and decision signatory included. */
    private static Attributes attributesOf(SeparationView s) {
        return new Attributes(s.legalGroundCode(), s.reasonCode(), s.requestDate(), s.noticeDate(), s.lastWorkingDate(),
                s.terminationDate(), s.accessRevocationDate(), s.specialTradeCode(), s.noticeExemptReasonCode(),
                s.protectedOverrideReason(), s.rehireEligible(), s.signatoryName(), s.signatoryTitle());
    }

    private SeparationResult result(SeparationView s, Derived d) {
        return new SeparationResult(s, d.notice(), d.protection(), d.rule().statutoryCode(), d.warnings());
    }

    /**
     * OD-8 (US-PPL-067): a personal e-mail is required unless HR records "no e-mail". A new address becomes the
     * person's primary PERSONAL_EMAIL ({@link PersonContactService} when the caller may update the person, else a
     * local insert with the same rules for the ESS employee).
     *
     * @return whether a personal e-mail is on file afterwards
     */
    private boolean personalEmail(Employment emp, Submission sub) {
        Optional<String> current = contacts.primaryPersonalEmail(emp.personId());
        if (sub.personalEmail() != null && !sub.personalEmail().isBlank()) {
            String value = PersonRules.contactValue("PERSONAL_EMAIL", sub.personalEmail());
            if (current.isPresent() && current.get().equals(value)) {
                return true;
            }
            if (access.can(PeoplePermissions.PERSON_UPDATE, emp.id(), clock.today())) {
                contacts.add(emp.personId(), new PersonContactService.ContactData("PERSONAL_EMAIL", value, true));
            } else {
                setOwnPersonalEmail(emp.personId(), value);
            }
            return true;
        }
        if (current.isPresent()) {
            return true;
        }
        if (sub.noPersonalEmail()) {
            return false;
        }
        throw ApiException.unprocessable("personal-email-required", "Capture the employee's personal e-mail for the "
                + "former-employee sign-in (OD-8), or record that the employee has no e-mail");
    }

    /**
     * OD-8 on the steps after submission that need the former-employee e-mail (approval: decision and
     * {@code TerminationScheduled}): an e-mail must still be on file unless HR recorded "no e-mail"
     * ({@code no_personal_email}).
     *
     * @return whether a personal e-mail is on file
     */
    private boolean requireEmailOrDeclared(SeparationView s) {
        boolean onFile = contacts.primaryPersonalEmail(s.personId()).isPresent();
        if (!onFile && !s.noPersonalEmail()) {
            throw ApiException.unprocessable("personal-email-required", "Employee " + s.employeeNo() + " has no "
                    + "personal e-mail on file for the former-employee sign-in (OD-8) and HR has not recorded that "
                    + "there is none");
        }
        return onFile;
    }

    private void setOwnPersonalEmail(UUID personId, String value) {
        UUID t = tenant();
        db.jdbc().sql("""
                        update hr.person_contact set is_primary = false, version = version + 1, updated_by = :a
                         where tenant_id = :t and person_id = :p and contact_type = 'PERSONAL_EMAIL' and is_primary""")
                .param("a", actor()).param("t", t).param("p", personId).update();
        db.jdbc().sql("""
                        insert into hr.person_contact (tenant_id, id, person_id, contact_type, contact_value, is_primary,
                               created_by, updated_by)
                        values (:t, :id, :p, 'PERSONAL_EMAIL', :v, true, :a, :a)""")
                .param("t", t).param("id", Ids.newId()).param("p", personId).param("v", value).param("a", actor())
                .update();
    }

    private UUID insertPeriod(SeparationView s, ServicePeriodData p) {
        if (!List.of("WORKED", "RECOGNISED_PRIOR", "PAID_SEVERANCE", "PAID_JOB_LOSS", "EXCLUDED")
                .contains(p.periodKind())) {
            throw ApiException.unprocessable("invalid-period-kind", "Period kind must be WORKED, RECOGNISED_PRIOR, "
                    + "PAID_SEVERANCE, PAID_JOB_LOSS or EXCLUDED");
        }
        if (p.periodFrom() == null || p.periodTo() == null || p.periodTo().isBefore(p.periodFrom())) {
            throw ApiException.unprocessable("invalid-period", "A period needs from <= to");
        }
        if (p.note() != null && p.note().startsWith(SeparationRecords.AUTO)) {
            throw ApiException.unprocessable("invalid-period", "The note prefix 'auto:' is reserved");
        }
        if (p.sourceEmploymentId() != null) {
            UUID person = db.jdbc().sql("select person_id from hr.employment where tenant_id = :t and id = :e")
                    .param("t", tenant()).param("e", p.sourceEmploymentId()).query(UUID.class).optional()
                    .orElseThrow(() -> ApiException.unprocessable("invalid-source-employment",
                            "Unknown source employment " + p.sourceEmploymentId()));
            if (!person.equals(s.personId())) {
                throw ApiException.unprocessable("invalid-source-employment",
                        "The source employment belongs to another person");
            }
        }
        return records.insert(s.id(), p.periodKind(), p.periodFrom(), p.periodTo(), p.sourceEmploymentId(), p.note());
    }

    private Map<String, Object> summary(SeparationView s) {
        Map<String, Object> m = new HashMap<>();
        m.put("employeeNo", s.employeeNo());
        m.put("legalGroundCode", s.legalGroundCode());
        m.put("lastWorkingDate", s.lastWorkingDate().toString());
        m.put("terminationDate", s.terminationDate().toString());
        m.put("noticeShortfallDays", s.noticeShortfallDays() == null ? 0 : s.noticeShortfallDays());
        m.put("protectedEmployee", s.protectedEmployee());
        return m;
    }

    void notifyHr(String event, SeparationView s, Map<String, Object> payload, String dedupeKey) {
        Optional<AuthorizationTarget.Employee> t = access.target(s.employmentId(), clock.today());
        if (t.isEmpty()) {
            return;
        }
        try {
            notifications.send(new Notifications.SendRequest(event, "hr",
                    new Recipients(null, List.of(Audience.permission(PeoplePermissions.SEPARATION_UPDATE, t.get()))),
                    payload, SeparationKeys.SUBJECT_TYPE, s.id(), "/people/separations/" + s.id(), dedupeKey, null));
        } catch (RuntimeException e) {
            log.warn("Notification {} for separation {} failed: {}", event, s.id(), e.getMessage());
        }
    }

    private Employment liveEmployment(UUID employmentId) {
        Employment emp = employments.require(employmentId);
        if (!List.of("ACTIVE", "SUSPENDED", "PRE_HIRE").contains(emp.status())) {
            throw ApiException.unprocessable("employment-not-active", "Employment " + emp.employeeNo() + " is "
                    + emp.status());
        }
        return emp;
    }

    private UUID ownEmployment(SelfServiceScope.Self me, UUID requested) {
        if (requested != null) {
            if (!me.employmentIds().contains(requested)) {
                throw ApiException.notFound("Employment", requested);
            }
            return requested;
        }
        List<UUID> live = me.employmentIds().stream()
                .filter(e -> employments.find(e).map(Employment::live).orElse(false)).toList();
        if (live.size() != 1) {
            throw ApiException.unprocessable("employment-required", "You have " + live.size()
                    + " current employments; choose the one you resign from");
        }
        return live.getFirst();
    }

    private AuthorizationTarget.Employee target(UUID employmentId) {
        return access.target(employmentId, clock.today())
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
    }

    private void requireScope(String permission, SeparationView s) {
        if (!access.can(permission, s.employmentId(), clock.today())) {
            throw ApiException.notFound("Separation", s.id());
        }
    }

    private static void requireStatus(SeparationView s, String status) {
        if (!status.equals(s.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Separation is " + s.status()
                    + ", expected " + status);
        }
    }

    private static void requireVersion(SeparationView s, int expected) {
        if (s.version() != expected) {
            throw ApiException.conflict("stale-version", "Separation was changed (version " + s.version()
                    + ", expected " + expected + "); reload and retry");
        }
    }

    private void setStatus(SeparationView s, String status) {
        db.jdbc().sql("""
                        update hr.separation set status = :s, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("s", status).param("a", actor()).param("t", tenant()).param("id", s.id()).update();
    }

    private void audit(String action, SeparationView s, Map<String, String> details) {
        Map<String, String> d = new HashMap<>(details);
        d.put("employmentId", s.employmentId().toString());
        audit.record(new BusinessAction(action, "hr", "separation", s.id(), s.legalEntityId(), d));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }
}
