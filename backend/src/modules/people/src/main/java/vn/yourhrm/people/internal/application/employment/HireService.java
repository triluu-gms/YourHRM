package vn.yourhrm.people.internal.application.employment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.Numbering;
import vn.yourhrm.people.api.EmploymentEvents.EmployeeHired;
import vn.yourhrm.people.api.EmploymentEvents.HireCancelled;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.assignment.AssignmentService;
import vn.yourhrm.people.internal.application.assignment.Warning;
import vn.yourhrm.people.internal.application.contract.ContractService;
import vn.yourhrm.people.internal.application.contract.ContractService.InitialSource;
import vn.yourhrm.people.internal.application.contract.ContractTerms;
import vn.yourhrm.people.internal.application.person.PersonService;
import vn.yourhrm.people.internal.application.person.PersonService.MatchQuery;
import vn.yourhrm.people.internal.application.person.PersonService.MatchResult;
import vn.yourhrm.people.internal.application.salary.SalaryService;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryTerms;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.assignment.ProcessedMarkers;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore.Row;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.entitlement.TenantLimits;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartResult;

/**
 * Direct hire (BR-PPL-001/002/003, US-PPL-018..021; BRD §3 P1 / P2). Recruitment hand-off and the rehire seniority
 * choice are Phase 2.
 *
 * <ol>
 *   <li>Person match ({@link PersonService#match} / {@link PersonService#createOrMatch}): an exact national-id /
 *       passport match blocks a new person (the wizard continues with that person as REHIRE or CONCURRENT); a tax-code
 *       / SI-number match only warns.</li>
 *   <li>A live (or later-ending) employment of the person in the same legal entity blocks: same-LE extra work is an
 *       assignment change (422 {@code same-legal-entity-employment}, {@code redirect = ASSIGNMENT_CHANGE});
 *       {@code employment_person_le_ex} is the DB guard (mapped to 422).</li>
 *   <li>Plan limit {@code limit.active_employees} (IN-23, through {@link TenantLimits}; absent = no limit).</li>
 *   <li>One People transaction: employment PRE_HIRE (employee no. from Core's {@code EMPLOYEE_NO}), HIRE decision
 *       (kept PENDING_APPROVAL until approved), first PRIMARY assignment, first contract (DRAFT, signed on paper later),
 *       first salary record; then {@code hr.HIRE} is started.</li>
 *   <li>Approval: the decision becomes APPROVED; a concurrent hire gets its default PIT primary designation; insurance
 *       participation is derived; a future hire date publishes {@code EmployeeHired} with {@code preview = true}, a
 *       hire date that has come activates at once. Rejection cancels the pre-hire.</li>
 *   <li>The daily job ({@link EmploymentJobService}) activates approved pre-hires on their hire date → final
 *       {@code EmployeeHired}. The first {@code AssignmentChanged} / {@code SalaryChanged} are held while the hire waits
 *       for approval (integration fix F1, {@code HireApprovalGate}) and released on activation (or by their daily job
 *       on the hire date once approved), each exactly once; {@code ContractChanged(SIGNED)} is published when the
 *       contract scan is recorded.</li>
 * </ol>
 * Missing mandatory data does not block: the employment is flagged incomplete ({@code missingData}).
 */
@Service
public class HireService {

    /** New person data for the wizard; null when hiring an existing person ({@code personId}). */
    public record PersonInput(PersonService.PersonData data, PersonService.Identifiers identifiers,
            boolean acknowledgeWarnings) {
    }

    /**
     * The wizard's submission.
     *
     * @param hireType optional NEW / REHIRE / CONCURRENT; derived from the person's employments and checked when given
     * @param contract its start defaults to the hire date
     */
    public record HireRequest(UUID personId, PersonInput person, String hireType, UUID legalEntityId,
            LocalDate hireDate, LocalDate originalHireDate, LocalDate seniorityDate, LocalDate probationEndDate,
            String workerCategoryCode, String employeeCategoryCode, String workingRegimeCode,
            AssignmentService.Attributes assignment, ContractTerms contract, SalaryTerms salary, String signatoryName,
            String signatoryTitle) {
    }

    /** {@code status}: PENDING_APPROVAL, APPROVED (future hire date) or ACTIVE. */
    public record HireResult(UUID employmentId, UUID personId, boolean personCreated, String employeeNo,
            String hireType, String status, UUID workflowInstanceId, UUID decisionId, String decisionNo,
            UUID assignmentId, UUID contractId, UUID salaryRecordId, List<Warning> warnings,
            List<String> missingData) {
    }

    /** A blocking candidate of the match step with what the wizard may do with it. */
    public record MatchOption(PersonService.Candidate candidate, List<EmploymentSummary> employments,
            List<String> options) {
    }

    public record EmploymentSummary(UUID employmentId, UUID legalEntityId, String employeeNo, String status,
            LocalDate hireDate, LocalDate endDate) {
    }

    public record MatchView(List<MatchOption> blocking, List<PersonService.Candidate> warnings) {
    }

    public record CancelRequest(String reasonCode, String reason, Integer version) {
    }

    private final HrDb db;
    private final EmploymentStore store;
    private final PersonService persons;
    private final AssignmentService assignments;
    private final ContractService contracts;
    private final SalaryService salaries;
    private final DecisionService decisions;
    private final PrimaryEmploymentService primaries;
    private final InsuranceParticipationService insurance;
    private final EmploymentService employmentViews;
    private final Savepoints savepoints;
    private final WorkflowService workflow;
    private final Numbering numbering;
    private final AuthorizationService authz;
    private final EmployeeAccess access;
    private final ObjectProvider<TenantLimits> limits;
    private final ProcessedMarkers markers;
    private final HrOutbox outbox;
    private final AuditLog audit;
    private final BusinessClock clock;

    public HireService(HrDb db, EmploymentStore store, PersonService persons, AssignmentService assignments,
            ContractService contracts, SalaryService salaries, DecisionService decisions,
            PrimaryEmploymentService primaries, InsuranceParticipationService insurance,
            EmploymentService employmentViews, Savepoints savepoints, WorkflowService workflow, Numbering numbering,
            AuthorizationService authz, EmployeeAccess access, ObjectProvider<TenantLimits> limits, ProcessedMarkers markers,
            HrOutbox outbox, AuditLog audit, BusinessClock clock) {
        this.db = db;
        this.store = store;
        this.persons = persons;
        this.assignments = assignments;
        this.contracts = contracts;
        this.salaries = salaries;
        this.decisions = decisions;
        this.primaries = primaries;
        this.insurance = insurance;
        this.employmentViews = employmentViews;
        this.savepoints = savepoints;
        this.workflow = workflow;
        this.numbering = numbering;
        this.authz = authz;
        this.access = access;
        this.limits = limits;
        this.markers = markers;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    // --------------------------------------------------------------------------------------------------- match

    /** Step 1 of the wizard (US-PPL-003/018): candidates with their employments and the possible hire options. */
    @HrTransactional(readOnly = true)
    public MatchView match(MatchQuery q, UUID legalEntityId, LocalDate hireDate) {
        if (!authz.hasTenantWidePermission(PeoplePermissions.EMPLOYMENT_CREATE)
                && (legalEntityId == null || !authz.can(PeoplePermissions.EMPLOYMENT_CREATE,
                        new AuthorizationTarget.LegalEntity(legalEntityId), clock.today()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "out-of-scope",
                    "Hiring into this legal entity is outside your data scope");
        }
        MatchResult m = persons.match(q);
        LocalDate d = hireDate == null ? clock.today() : hireDate;
        List<MatchOption> blocking = new ArrayList<>();
        for (PersonService.Candidate c : m.blocking()) {
            List<Row> emps = store.ofPerson(c.personId());
            List<String> options = new ArrayList<>();
            boolean sameLe = legalEntityId != null && emps.stream().anyMatch(e -> e.legalEntityId().equals(legalEntityId)
                    && blocksSameLe(e, d));
            if (sameLe) {
                options.add("ASSIGNMENT_CHANGE");
            } else {
                options.add(hireTypeFor(emps, d));
            }
            blocking.add(new MatchOption(c, emps.stream().map(e -> new EmploymentSummary(e.id(), e.legalEntityId(),
                    e.employeeNo(), e.status(), e.hireDate(), e.endDate())).toList(), options));
        }
        return new MatchView(blocking, m.warnings());
    }

    // -------------------------------------------------------------------------------------------------- submit

    @HrTransactional
    public HireResult hire(HireRequest req) {
        requireFields(req);
        UUID le = req.legalEntityId();
        LocalDate hireDate = req.hireDate();
        if (!mayHireInto(le, req.assignment().orgUnitId(), req.assignment().locationId(), hireDate)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "out-of-scope",
                    "Hiring into this legal entity / org unit is outside your data scope");
        }
        // 1. person
        UUID personId;
        boolean created = false;
        if (req.personId() != null) {
            personId = persons.find(req.personId()).map(PersonService.PersonView::id)
                    .orElseThrow(() -> ApiException.notFound("Person", req.personId()));
        } else {
            if (req.person() == null || req.person().data() == null) {
                throw ApiException.unprocessable("person-required", "Either personId or person data is required");
            }
            var r = persons.createOrMatch(new PersonService.CreatePerson(req.person().data(),
                    req.person().identifiers(), "APP", req.person().acknowledgeWarnings()));
            if (!r.created()) {
                var c = r.match().blocking().getFirst();
                throw new ApiException(HttpStatus.CONFLICT, "person-exists", "A person with the same national "
                        + "identifier exists (BR-PPL-001): hire that person (rehire / concurrent employment)",
                        Map.of("personId", c.personId(), "matchedOn", c.matchedOn()));
            }
            personId = r.personId();
            created = true;
        }
        store.lockPerson(personId);
        // 2. per-LE rule and hire type
        List<Row> existing = store.ofPerson(personId);
        existing.stream().filter(e -> e.legalEntityId().equals(le) && blocksSameLe(e, hireDate)).findFirst()
                .ifPresent(e -> {
                    throw ApiException.unprocessable("same-legal-entity-employment", "The person already has an "
                            + "employment (" + e.employeeNo() + ") in this legal entity: extra work in the same legal "
                            + "entity is an assignment change, not a second employment (BR-PPL-002)",
                            Map.of("existingEmploymentId", e.id(), "redirect", "ASSIGNMENT_CHANGE"));
                });
        String hireType = hireTypeFor(existing, hireDate);
        if (req.hireType() != null && !req.hireType().equals(hireType)) {
            throw ApiException.unprocessable("hire-type-mismatch", "This hire is a " + hireType + " hire of the person",
                    Map.of("expected", hireType));
        }
        // 3. licence
        checkLicence(true);
        // 4. employment
        String employeeNo = nextEmployeeNo(le, hireDate);
        UUID employmentId = Ids.newId();
        UUID previous = "REHIRE".equals(hireType) ? existing.stream().filter(e -> !"CANCELLED".equals(e.status()))
                .reduce((a, b) -> b).map(Row::id).orElse(null) : null;
        LocalDate originalHire = req.originalHireDate() != null ? req.originalHireDate()
                : existing.stream().filter(e -> !"CANCELLED".equals(e.status())).map(Row::hireDate).min(LocalDate::compareTo)
                        .filter(d -> d.isBefore(hireDate)).orElse(hireDate);
        if (originalHire.isAfter(hireDate)) {
            throw ApiException.unprocessable("invalid-original-hire-date",
                    "The original hire date cannot be after the hire date");
        }
        Row row = new Row(employmentId, personId, le, employeeNo, hireType, hireDate, originalHire,
                req.seniorityDate() != null ? req.seniorityDate() : hireDate, req.probationEndDate(), null, null,
                "PRE_HIRE", req.workerCategoryCode(), req.employeeCategoryCode(), req.workingRegimeCode(), null,
                previous, "APP", 0);
        try {
            store.insert(row, actor());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
        // 5. decision (pending until approved), assignment, contract, salary
        // F2 (integration): the foundation now issues the decision PENDING_APPROVAL itself
        var decision = decisions.issuePending(new DecisionService.Issue(le, "HIRE", employmentId, hireDate,
                req.signatoryName(), req.signatoryTitle(), null));
        var assignment = assignments.createInitial(employmentId, req.assignment(), hireDate, decision.id(), "APP");
        ContractTerms terms = req.contract().startDate() != null ? req.contract()
                : req.contract().withTerm(req.contract().contractTypeCode(), hireDate, req.contract().endDate());
        var contract = contracts.createInitial(employmentId, terms, InitialSource.app());
        var salary = salaries.createInitial(employmentId, req.salary(), hireDate, decision.id(), null, "APP");
        List<Warning> warnings = new ArrayList<>(salary.warnings());
        // 6. approval
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("employeeNo", employeeNo);
        summary.put("hireType", hireType);
        summary.put("hireDate", hireDate.toString());
        summary.put("workerCategoryCode", req.workerCategoryCode());
        summary.put("contractTypeCode", terms.contractTypeCode());
        StartResult started = workflow.start(new StartRequest(EmploymentKeys.HIRE_PROCESS,
                EmploymentKeys.HIRE_SUBJECT, employmentId, 0, EmploymentKeys.HIRE_SUBJECT + ":" + employmentId, null,
                personId, le, req.assignment().orgUnitId(), req.assignment().locationId(), null, summary,
                Map.of("hireType", hireType, "workerCategoryCode", req.workerCategoryCode()), Map.of()));
        HireDecisions.linkWorkflow(db, decision.id(), started.instanceId());
        audit.record(new BusinessAction("REQUEST_HIRE", "hr", "employment", employmentId, le,
                PrimaryEmploymentService.details("employeeNo", employeeNo, "hireType", hireType, "hireDate", hireDate,
                        "personCreated", created, "workflowInstanceId", started.instanceId())));
        String status = "PENDING_APPROVAL";
        if ("APPROVED".equals(started.status())) {
            status = resolve(employmentId, started.instanceId(), "APPROVED").map(Row::status)
                    .map(s -> "ACTIVE".equals(s) ? "ACTIVE" : "APPROVED").orElse("APPROVED");
        }
        Row now = store.find(employmentId).orElseThrow();
        return new HireResult(employmentId, personId, created, employeeNo, hireType, status, started.instanceId(),
                decision.id(), decision.decisionNo(), assignment.id(), contract.id(), salary.salaryRecordId(),
                warnings, employmentViews.missingData(now));
    }

    /**
     * The new employment has no id yet ({@code AuthorizationTarget.Employee} needs one): the hire is in scope when the
     * caller's {@code hr.employment.create} grant covers its legal entity, its org unit or its work location.
     */
    private boolean mayHireInto(UUID le, UUID orgUnit, UUID location, LocalDate d) {
        String p = PeoplePermissions.EMPLOYMENT_CREATE;
        return authz.can(p, new AuthorizationTarget.LegalEntity(le), d)
                || (orgUnit != null && authz.can(p, new AuthorizationTarget.OrgUnit(orgUnit), d))
                || (location != null && authz.can(p, new AuthorizationTarget.Location(location), d));
    }

    private static void requireFields(HireRequest r) {
        List<String> missing = new ArrayList<>();
        if (r.legalEntityId() == null) {
            missing.add("legalEntityId");
        }
        if (r.hireDate() == null) {
            missing.add("hireDate");
        }
        if (r.workerCategoryCode() == null || r.workerCategoryCode().isBlank()) {
            missing.add("workerCategoryCode");
        }
        if (r.assignment() == null) {
            missing.add("assignment");
        }
        if (r.contract() == null) {
            missing.add("contract");
        }
        if (r.salary() == null) {
            missing.add("salary");
        }
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("hire-data-required", "Missing hire data: " + missing,
                    Map.of("fields", missing));
        }
    }

    /** A non-cancelled employment of the LE that has not ended before {@code hireDate} blocks a new one there. */
    private static boolean blocksSameLe(Row e, LocalDate hireDate) {
        return !"CANCELLED".equals(e.status()) && (e.endDate() == null || !e.endDate().isBefore(hireDate));
    }

    private static String hireTypeFor(List<Row> existing, LocalDate hireDate) {
        boolean liveElsewhere = existing.stream().anyMatch(e -> !"CANCELLED".equals(e.status())
                && (e.endDate() == null || !e.endDate().isBefore(hireDate)));
        if (liveElsewhere) {
            return "CONCURRENT";
        }
        return existing.stream().anyMatch(e -> !"CANCELLED".equals(e.status())) ? "REHIRE" : "NEW";
    }

    private String nextEmployeeNo(UUID le, LocalDate hireDate) {
        try {
            return numbering.next(EmploymentKeys.EMPLOYEE_NO_SEQUENCE, le, hireDate);
        } catch (RuntimeException ex) {
            throw ApiException.unprocessable("numbering-not-configured", "Number series "
                    + EmploymentKeys.EMPLOYEE_NO_SEQUENCE + " is not configured: " + ex.getMessage(),
                    Map.of("sequence", EmploymentKeys.EMPLOYEE_NO_SEQUENCE));
        }
    }

    /**
     * IN-23 plan limit of active employees (a 2-LE person counts twice, SUSPENDED is not counted). At submission
     * pending pre-hires count too (they will become active); at activation only ACTIVE ones.
     */
    private boolean withinLicence(boolean includePreHires) {
        TenantLimits l = limits.getIfAvailable();
        OptionalLong max = l == null ? OptionalLong.empty() : l.limit(EmploymentKeys.LIMIT_ACTIVE_EMPLOYEES);
        if (max.isEmpty()) {
            return true;
        }
        long count = db.jdbc().sql("select count(*) from hr.employment where tenant_id = :t and status in (:s)")
                .param("t", tenant()).param("s", includePreHires ? List.of("ACTIVE", "PRE_HIRE") : List.of("ACTIVE"))
                .query(Long.class).single();
        return count + 1 <= max.getAsLong();
    }

    private void checkLicence(boolean includePreHires) {
        if (!withinLicence(includePreHires)) {
            throw ApiException.unprocessable("licence-limit-reached", "The subscription's limit of active employees "
                    + "is reached (IN-23)", Map.of("limit", EmploymentKeys.LIMIT_ACTIVE_EMPLOYEES));
        }
    }

    private static ApiException translate(DataIntegrityViolationException ex) {
        String m = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (m.contains("employment_person_le_ex")) {
            return ApiException.unprocessable("same-legal-entity-employment", "The person already has an employment "
                    + "in this legal entity for this period (BR-PPL-002); use an assignment change",
                    Map.of("redirect", "ASSIGNMENT_CHANGE"));
        }
        if (m.contains("employment_no_uq")) {
            return ApiException.conflict("employee-no-taken", "The employee number is already used in the tenant");
        }
        return ApiException.unprocessable("invalid-employment", "The employment violates a database rule");
    }

    // ------------------------------------------------------------------------------------------------ approval

    /**
     * Applies the outcome of {@code hr.HIRE}; idempotent per employment. Returns the employment after the change, empty
     * when nothing was done (already handled, or the pre-hire was cancelled meanwhile).
     */
    @HrTransactional
    public Optional<Row> resolve(UUID employmentId, UUID workflowInstanceId, String outcome) {
        markers.lock(EmploymentKeys.HIRE_APPROVAL_MARKER, employmentId);
        if (markers.exists(EmploymentKeys.HIRE_APPROVAL_MARKER, employmentId)) {
            return Optional.empty();
        }
        Row row = store.lock(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (!"PRE_HIRE".equals(row.status())) {
            markers.mark(EmploymentKeys.HIRE_APPROVAL_MARKER, employmentId, "core.WorkflowCompleted", "SKIPPED");
            return Optional.empty();
        }
        if (!"APPROVED".equals(outcome)) {
            cancelInternal(row, "APPROVAL_REJECTED", "APPROVAL_" + outcome, "Hire approval " + outcome);
            markers.mark(EmploymentKeys.HIRE_APPROVAL_MARKER, employmentId, "core.WorkflowCompleted", "SKIPPED");
            return store.find(employmentId);
        }
        // F2 (integration): PENDING_APPROVAL -> APPROVED through the foundation's DecisionService
        HireDecisions.of(db, employmentId).filter(d -> !"CANCELLED".equals(d.status()))
                .ifPresent(d -> decisions.approve(d.id(), workflowInstanceId));
        if (store.ofPerson(row.personId()).stream().anyMatch(e -> !e.id().equals(row.id())
                && e.covers(row.hireDate()))) {
            primaries.onConcurrentHireApproved(row);
        }
        savepoints.tryRun("Insurance participation after hire " + row.employeeNo(),
                () -> insurance.derive(row.personId(), row.hireDate(), "EMPLOYMENT_STARTED"), 0);
        audit.record(new BusinessAction("APPROVE_HIRE", "hr", "employment", employmentId, row.legalEntityId(),
                PrimaryEmploymentService.details("employeeNo", row.employeeNo(), "workflowInstanceId",
                        workflowInstanceId)));
        if (row.hireDate().isAfter(clock.today())) {
            publishHired(row, true);
        } else {
            activate(row);
        }
        markers.mark(EmploymentKeys.HIRE_APPROVAL_MARKER, employmentId, "core.WorkflowCompleted", "PROCESSED");
        return store.find(employmentId);
    }

    /**
     * PRE_HIRE → ACTIVE for an approved hire whose date has come (final {@code EmployeeHired}); idempotent. Returns
     * false when the plan limit blocks it (stays PRE_HIRE, retried by the daily job).
     */
    @HrTransactional
    public boolean activate(Row row) {
        markers.lock(EmploymentKeys.ACTIVATION_MARKER, row.id());
        if (markers.exists(EmploymentKeys.ACTIVATION_MARKER, row.id())) {
            return true;
        }
        if (!withinLicence(false)) {
            audit.record(new BusinessAction("HIRE_ACTIVATION_BLOCKED", "hr", "employment", row.id(),
                    row.legalEntityId(), Map.of("reason", "licence-limit-reached")));
            return false;
        }
        if (!store.transition(row.id(), "PRE_HIRE", "ACTIVE", actor())) {
            return false;
        }
        publishHired(store.find(row.id()).orElseThrow(), false);
        // F1 (integration): the first AssignmentChanged / SalaryChanged were held while the hire waited for approval;
        // announce them now (no-op when the creators or the daily jobs already published them: shared due markers).
        assignments.releaseHeldInitialEvents(row.id(), row.hireDate());
        salaries.releaseHeldInitialEvents(row.id(), row.hireDate());
        markers.mark(EmploymentKeys.ACTIVATION_MARKER, row.id(), "people.EmployeeHired", "PROCESSED");
        return true;
    }

    private void publishHired(Row r, boolean preview) {
        Map<String, Object> pa = db.jdbc().sql("""
                        select org_unit_id, position_id, job_title_id, location_id, manager_employment_id
                          from hr.assignment where tenant_id = :t and employment_id = :e and assignment_type = 'PRIMARY'
                           and superseded_at is null
                         order by (valid_during @> cast(:d as date)) desc, valid_from limit 1""")
                .param("t", tenant()).param("e", r.id()).param("d", r.hireDate())
                .query().listOfRows().stream().findFirst().orElse(Map.of());
        String fullName = db.jdbc().sql("select full_name from hr.person where tenant_id = :t and id = :p")
                .param("t", tenant()).param("p", r.personId()).query(String.class).single();
        var decision = HireDecisions.of(db, r.id());
        outbox.publish(new EmployeeHired(tenant(), r.legalEntityId(), r.personId(), r.id(), r.hireDate(), preview,
                r.hireType(), r.employeeNo(), fullName, r.hireDate(), r.originalHireDate(), r.seniorityDate(),
                r.workerCategoryCode(), r.employeeCategoryCode(), r.workingRegimeCode(), r.previousEmploymentId(),
                (UUID) pa.get("org_unit_id"), (UUID) pa.get("position_id"), (UUID) pa.get("job_title_id"),
                (UUID) pa.get("location_id"), (UUID) pa.get("manager_employment_id"),
                decision.map(HireDecisions.HireDecision::id).orElse(null),
                decision.map(HireDecisions.HireDecision::decisionNo).orElse(null), employmentViews.missingData(r)));
    }

    // -------------------------------------------------------------------------------------------------- cancel

    /** US-PPL-020: cancels a pre-hire before it starts (reason required) → {@code people.HireCancelled}. */
    @HrTransactional
    public Row cancel(UUID employmentId, CancelRequest req) {
        Row row = store.lock(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        access.require(PeoplePermissions.EMPLOYMENT_CREATE, employmentId, row.hireDate());
        if (req.reason() == null || req.reason().isBlank()) {
            throw ApiException.unprocessable("reason-required", "A reason is required to cancel a hire");
        }
        if (!"PRE_HIRE".equals(row.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Only a pre-hire can be cancelled; "
                    + row.employeeNo() + " is " + row.status() + " (an active employee leaves through a separation)");
        }
        if (req.version() != null && req.version() != row.version()) {
            throw ApiException.conflict("stale-version", "Employment " + row.employeeNo() + " was changed (version "
                    + row.version() + "); reload and retry");
        }
        cancelInternal(row, "NO_SHOW", req.reasonCode(), req.reason());
        return store.find(employmentId).orElseThrow();
    }

    /**
     * PRE_HIRE → CANCELLED and closing of the child rows: DRAFT contracts CANCELLED, the HIRE decision CANCELLED (it
     * keeps its number), PIT designations withdrawn, insurance participation re-derived (rows CANCELLED). The first
     * assignment and salary versions are bi-temporal and immutable: they stay as the history of the cancelled
     * employment and their daily jobs skip CANCELLED employments. The CANCELLED status frees
     * {@code employment_person_le_ex}.
     */
    private void cancelInternal(Row row, String kind, String reasonCode, String reason) {
        boolean previewPublished = HireDecisions.approved(db, row.id());
        if (!store.transition(row.id(), "PRE_HIRE", "CANCELLED", actor())) {
            throw ApiException.conflict("stale-version", "Employment " + row.employeeNo() + " changed meanwhile");
        }
        db.jdbc().sql("""
                        update hr.contract set status = 'CANCELLED', version = version + 1, updated_by = :a
                         where tenant_id = :t and employment_id = :e and status = 'DRAFT'""")
                .param("a", actor()).param("t", tenant()).param("e", row.id()).update();
        HireDecisions.of(db, row.id()).filter(d -> !"CANCELLED".equals(d.status()))
                .ifPresent(d -> decisions.cancel(d.id(), d.version()));
        primaries.onHireCancelled(row);
        savepoints.tryRun("Insurance participation after cancelled hire " + row.employeeNo(),
                () -> insurance.derive(row.personId(), row.hireDate(), "HIRE_CANCELLED"), 0);
        outbox.publish(new HireCancelled(tenant(), row.legalEntityId(), row.personId(), row.id(), row.hireDate(),
                row.employeeNo(), row.hireDate(), kind, reasonCode, reason, previewPublished));
        audit.record(new BusinessAction("CANCEL_HIRE", "hr", "employment", row.id(), row.legalEntityId(),
                PrimaryEmploymentService.details("employeeNo", row.employeeNo(), "kind", kind, "reasonCode",
                        reasonCode, "reason", reason)));
    }

    /** Approved pre-hires whose hire date has come (daily job). */
    List<Row> dueActivations(LocalDate today) {
        return db.jdbc().sql("""
                        select e.* from hr.employment e
                         where e.tenant_id = :t and e.status = 'PRE_HIRE' and e.hire_date <= :d
                           and (e.source <> 'APP' or exists (select 1 from hr.decision d
                                where d.tenant_id = e.tenant_id and d.employment_id = e.id
                                  and d.decision_type_code = 'HIRE' and d.status in ('APPROVED','SIGNED')))
                         order by e.hire_date, e.id""")
                .param("t", tenant()).param("d", today).query(EmploymentStore::map).list();
    }
}
