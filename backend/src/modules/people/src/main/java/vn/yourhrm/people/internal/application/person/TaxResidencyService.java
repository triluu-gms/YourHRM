package vn.yourhrm.people.internal.application.person;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PersonEvents.PersonalDataChanged;
import vn.yourhrm.people.api.PersonEvents.PresencePeriod;
import vn.yourhrm.people.api.PersonEvents.TaxResidency;
import vn.yourhrm.people.api.PersonResidencyProposals;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.HistoryEntry;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartResult;

/**
 * Tax residency and presence in Vietnam (BR-PPL-023, US-PPL-013/014; GAP-048). People records the PIT residency
 * status per person (Pattern A, {@code valid_to} EXCLUSIVE) and never computes the 183-day test:
 * <ul>
 *   <li>HR submits a status (with basis, arrival / departure dates, optional tax year and days in VN); it is
 *       approved through Core's process type {@code hr.TAX_RESIDENCY}; on approval the APPROVED row covering the new
 *       start is closed and the new row approved in one People transaction, so APPROVED rows never overlap;</li>
 *   <li>Payroll's 183-day proposals ({@link PersonResidencyProposals}, IN-32) land in a review queue; HR's approval
 *       there is the approval (source PAYROLL_PROPOSAL), a rejection keeps its reason in the audit trail;</li>
 *   <li>entry / exit dates ({@code hr.vn_presence_period}, non-overlapping) are maintained as input for Payroll.</li>
 * </ul>
 * Approved changes and presence changes publish {@code people.PersonalDataChanged} (status, basis, validity,
 * arrival / departure, entry / exit dates).
 */
@Service
public class TaxResidencyService implements PersonResidencyProposals {

    public static final Set<String> RESIDENT_BASES = Set.of("DAYS_183", "MONTHS_12", "PERMANENT_RESIDENCE", "LEASE_183");
    public static final Set<String> NON_RESIDENT_BASES = Set.of("NOT_MET", "TREATY_TIE_BREAKER");
    public static final String SUBJECT_TYPE = "hr.tax_residency";

    public record ResidencyData(String status, String basis, LocalDate validFrom, LocalDate validTo,
            LocalDate arrivalDate, LocalDate departureDate, Integer taxYear, Integer daysInVn) {
    }

    public record ResidencyView(UUID id, UUID personId, String status, String basis, LocalDate validFrom,
            LocalDate validTo, LocalDate arrivalDate, LocalDate departureDate, Integer taxYear, Integer daysInVn,
            String source, String proposalRef, String approvalStatus, UUID approvedBy, Instant approvedAt,
            UUID evidenceDocumentId, int version) {
    }

    public record PresenceView(UUID id, UUID personId, LocalDate entryDate, LocalDate exitDate, UUID evidenceDocumentId,
            int version) {
    }

    /** A submitted change and the state of its approval. */
    public record Submitted(ResidencyView residency, UUID workflowInstanceId, String workflowStatus) {
    }

    private final HrDb db;
    private final PersonSupport support;
    private final HrOutbox outbox;
    private final WorkflowService workflow;
    private final AuditLog audit;

    public TaxResidencyService(HrDb db, PersonSupport support, HrOutbox outbox, WorkflowService workflow,
            AuditLog audit) {
        this.db = db;
        this.support = support;
        this.outbox = outbox;
        this.workflow = workflow;
        this.audit = audit;
    }

    // ================================================================================================ residency (013)

    @HrTransactional(readOnly = true)
    public List<ResidencyView> list(UUID personId) {
        support.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, personId);
        return db.jdbc().sql("select * from hr.tax_residency where tenant_id = :t and person_id = :p order by valid_from, created_at")
                .param("t", support.tenant()).param("p", personId).query((rs, n) -> view(rs)).list();
    }

    /** HR records a residency status; it takes effect when approved through {@code hr.TAX_RESIDENCY}. */
    @HrTransactional
    public Submitted submit(UUID personId, ResidencyData data) {
        support.requirePerson(PeoplePermissions.TAX_RESIDENCY_UPDATE, personId);
        support.lockPerson(personId);
        ResidencyData d = validate(data);
        UUID t = support.tenant();
        boolean pending = db.jdbc().sql("""
                        select exists (select 1 from hr.tax_residency where tenant_id = :t and person_id = :p
                                          and approval_status in ('DRAFT','PENDING_APPROVAL') and source <> 'PAYROLL_PROPOSAL')""")
                .param("t", t).param("p", personId).query(Boolean.class).single();
        if (pending) {
            throw ApiException.conflict("tax-residency-pending", "A tax-residency change of this person is already "
                    + "waiting for approval");
        }
        requireNoLaterApproved(personId, d.validFrom(), null);
        UUID id = Ids.newId();
        insert(id, personId, d, "APP", null);
        Optional<AuthorizationTarget.Employee> emp = support.employeeTargets(personId, support.today()).stream()
                .filter(AuthorizationTarget.Employee.class::isInstance).map(AuthorizationTarget.Employee.class::cast)
                .findFirst();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", d.status());
        summary.put("basis", d.basis());
        summary.put("validFrom", d.validFrom().toString());
        if (d.validTo() != null) {
            summary.put("validTo", d.validTo().toString());
        }
        StartResult started = workflow.start(new StartRequest(PersonKeys.PROCESS_TAX_RESIDENCY, SUBJECT_TYPE, id, 0,
                SUBJECT_TYPE + ":" + id, null, personId, emp.map(AuthorizationTarget.Employee::legalEntityId).orElse(null),
                emp.map(AuthorizationTarget.Employee::orgUnitId).orElse(null),
                emp.map(AuthorizationTarget.Employee::locationId).orElse(null), null, summary,
                Map.of("status", d.status(), "basis", d.basis()), Map.of()));
        if ("APPROVED".equals(started.status())) {
            approve(id, support.actor());                  // every step skipped: approved at once (idempotent)
        }
        return new Submitted(find(id), started.instanceId(), started.status());
    }

    /** Applies a {@code hr.TAX_RESIDENCY} outcome (called by the workflow listener). Idempotent. */
    @HrTransactional
    public void applyOutcome(WorkflowCompleted e) {
        String outcome = e.outcome();
        if ("APPROVED".equals(outcome)) {
            UUID approver = e.history().stream().filter(h -> "APPROVE".equals(h.action()))
                    .reduce((a, b) -> b).map(HistoryEntry::actorUserId).orElse(support.actor());
            approve(e.subjectId(), approver);
        } else {
            db.jdbc().sql("""
                            update hr.tax_residency set approval_status = :s, version = version + 1, updated_by = :a
                             where tenant_id = :t and id = :id and approval_status = 'PENDING_APPROVAL'""")
                    .param("s", "REJECTED".equals(outcome) ? "REJECTED" : "CANCELLED").param("a", support.actor())
                    .param("t", support.tenant()).param("id", e.subjectId()).update();
        }
    }

    @HrTransactional
    public ResidencyView attachEvidence(UUID personId, UUID id, UUID documentId) {
        support.requirePerson(PeoplePermissions.TAX_RESIDENCY_UPDATE, personId);
        owned(personId, id);
        support.requireDocument(documentId, PersonKeys.DOC_TAX_RESIDENCY_EVIDENCE, PersonKeys.OWNER_TAX_RESIDENCY, id);
        db.jdbc().sql("""
                        update hr.tax_residency set evidence_document_id = :d, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("d", documentId).param("a", support.actor()).param("t", support.tenant()).param("id", id).update();
        return find(id);
    }

    /**
     * Approves a pending row: the APPROVED row covering its start is closed at that date, then the row is approved
     * (one transaction, BR-PPL-023 "no overlap"). An APPROVED row starting on / after it blocks.
     */
    private void approve(UUID id, UUID approver) {
        UUID t = support.tenant();
        ResidencyView r = db.jdbc().sql("select * from hr.tax_residency where tenant_id = :t and id = :id for update")
                .param("t", t).param("id", id).query((rs, n) -> view(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Tax residency", id));
        if (!"PENDING_APPROVAL".equals(r.approvalStatus())) {
            return;
        }
        support.lockPerson(r.personId());
        requireNoLaterApproved(r.personId(), r.validFrom(), id);
        db.jdbc().sql("""
                        update hr.tax_residency set valid_to = :from, version = version + 1, updated_by = :a
                         where tenant_id = :t and person_id = :p and approval_status = 'APPROVED' and id <> :id
                           and valid_from < :from and (valid_to is null or valid_to > :from)""")
                .param("from", r.validFrom()).param("a", support.actor()).param("t", t).param("p", r.personId())
                .param("id", id).update();
        try {
            db.jdbc().sql("""
                            update hr.tax_residency set approval_status = 'APPROVED', approved_by = :by, approved_at = now(),
                                   version = version + 1, updated_by = :a
                             where tenant_id = :t and id = :id""")
                    .param("by", approver).param("a", support.actor()).param("t", t).param("id", id).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("tax-residency-overlap", "The period overlaps an approved tax-residency status");
        }
        ResidencyView a = find(id);
        outbox.publish(new PersonalDataChanged(t, a.personId(), a.validFrom(), List.of("tax_residency"), null,
                new TaxResidency(a.id(), a.status(), a.basis(), a.validFrom(), a.validTo(), a.arrivalDate(),
                        a.departureDate(), a.source()), null, "TAX_RESIDENCY_APPROVED"));
    }

    private void requireNoLaterApproved(UUID personId, LocalDate from, UUID except) {
        boolean later = db.jdbc().sql("""
                        select exists (select 1 from hr.tax_residency where tenant_id = :t and person_id = :p
                                          and approval_status = 'APPROVED' and valid_from >= :from
                                          and (cast(:x as uuid) is null or id <> :x))""")
                .param("t", support.tenant()).param("p", personId).param("from", from).param("x", except)
                .query(Boolean.class).single();
        if (later) {
            throw ApiException.unprocessable("tax-residency-overlap", "An approved tax-residency status starts on or "
                    + "after " + from + "; residency history is closed forward only");
        }
    }

    // ================================================================================================ proposals (014)

    /** IN-32 intake (see {@link PersonResidencyProposals}); idempotent on the proposal reference. */
    @Override
    @HrTransactional
    public UUID receive(Proposal p) {
        UUID t = support.tenant();
        if (p.proposalRef() == null || p.proposalRef().isBlank()) {
            throw ApiException.unprocessable("invalid-proposal", "A proposal needs its reference");
        }
        Optional<UUID> existing = db.jdbc().sql("""
                        select id from hr.tax_residency where tenant_id = :t and source = 'PAYROLL_PROPOSAL'
                           and proposal_ref = :ref""")
                .param("t", t).param("ref", p.proposalRef()).query(UUID.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        support.lockPerson(p.personId());
        ResidencyData d = validate(new ResidencyData(p.status(), p.basis(), p.validFrom(), p.validTo(), null, null,
                (int) p.taxYear(), p.daysInVn() == null ? null : p.daysInVn().intValue()));
        UUID id = Ids.newId();
        insert(id, p.personId(), d, "PAYROLL_PROPOSAL", p.proposalRef());
        return id;
    }

    /** HR's review queue of pending Payroll proposals in the caller's scope (persons visible to the caller). */
    @HrTransactional(readOnly = true)
    public List<ResidencyView> proposals() {
        return db.jdbc().sql("""
                        select * from hr.tax_residency where tenant_id = :t and source = 'PAYROLL_PROPOSAL'
                           and approval_status = 'PENDING_APPROVAL' order by created_at""")
                .param("t", support.tenant()).query((rs, n) -> view(rs)).list().stream()
                .filter(r -> visible(r.personId())).toList();
    }

    /** HR approves a Payroll proposal: it becomes the approved status (source PAYROLL_PROPOSAL). */
    @HrTransactional
    public ResidencyView approveProposal(UUID id, int version) {
        ResidencyView r = proposal(id, version);
        approve(r.id(), support.actor());
        return find(id);
    }

    /** HR rejects a Payroll proposal; the reason is kept in the audit trail (no column in the DDL). */
    @HrTransactional
    public ResidencyView rejectProposal(UUID id, int version, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason-required", "A rejection needs a reason");
        }
        ResidencyView r = proposal(id, version);
        db.jdbc().sql("""
                        update hr.tax_residency set approval_status = 'REJECTED', version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("a", support.actor()).param("t", support.tenant()).param("id", r.id()).update();
        audit.record(new AuditLog.BusinessAction("REJECT", "hr", "tax_residency", id, null,
                Map.of("reason", reason.trim(), "proposalRef", String.valueOf(r.proposalRef()),
                        "personId", r.personId().toString())));
        return find(id);
    }

    private ResidencyView proposal(UUID id, int version) {
        ResidencyView r = db.jdbc().sql("select * from hr.tax_residency where tenant_id = :t and id = :id for update")
                .param("t", support.tenant()).param("id", id).query((rs, n) -> view(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Residency proposal", id));
        support.requirePerson(PeoplePermissions.TAX_RESIDENCY_UPDATE, r.personId());
        if (!"PAYROLL_PROPOSAL".equals(r.source())) {
            throw ApiException.notFound("Residency proposal", id);
        }
        if (r.version() != version) {
            throw ApiException.conflict("stale-version", "Proposal " + id + " was changed meanwhile; reload and retry");
        }
        if (!"PENDING_APPROVAL".equals(r.approvalStatus())) {
            throw ApiException.unprocessable("invalid-status-transition", "Proposal " + id + " is " + r.approvalStatus());
        }
        return r;
    }

    private boolean visible(UUID personId) {
        try {
            support.requirePerson(PeoplePermissions.TAX_RESIDENCY_UPDATE, personId);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    // ================================================================================================ presence periods

    @HrTransactional(readOnly = true)
    public List<PresenceView> presence(UUID personId) {
        support.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, personId);
        return db.jdbc().sql("select * from hr.vn_presence_period where tenant_id = :t and person_id = :p order by entry_date")
                .param("t", support.tenant()).param("p", personId).query((rs, n) -> presenceView(rs)).list();
    }

    /** Records an entry into VN (and the exit, INCLUSIVE last day, when known); periods never overlap. */
    @HrTransactional
    public PresenceView addPresence(UUID personId, LocalDate entryDate, LocalDate exitDate) {
        support.requirePerson(PeoplePermissions.TAX_RESIDENCY_UPDATE, personId);
        support.lockPerson(personId);
        checkPresence(entryDate, exitDate);
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into hr.vn_presence_period (tenant_id, id, person_id, entry_date, exit_date, created_by,
                                   updated_by) values (:t, :id, :p, :in, :out, :a, :a)""")
                    .param("t", support.tenant()).param("id", id).param("p", personId).param("in", entryDate)
                    .param("out", exitDate).param("a", support.actor()).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("presence-overlap", "The period overlaps another stay in Vietnam");
        }
        return publishPresence(personId, id);
    }

    @HrTransactional
    public PresenceView changePresence(UUID personId, UUID id, int version, LocalDate entryDate, LocalDate exitDate) {
        support.requirePerson(PeoplePermissions.TAX_RESIDENCY_UPDATE, personId);
        checkPresence(entryDate, exitDate);
        int updated;
        try {
            updated = db.jdbc().sql("""
                            update hr.vn_presence_period set entry_date = :in, exit_date = :out, version = version + 1,
                                   updated_by = :a
                             where tenant_id = :t and id = :id and person_id = :p and version = :v""")
                    .param("in", entryDate).param("out", exitDate).param("a", support.actor())
                    .param("t", support.tenant()).param("id", id).param("p", personId).param("v", version).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("presence-overlap", "The period overlaps another stay in Vietnam");
        }
        PersonSupport.requireUpdated(updated, "Presence period", id);
        return publishPresence(personId, id);
    }

    private PresenceView publishPresence(UUID personId, UUID id) {
        PresenceView v = db.jdbc().sql("select * from hr.vn_presence_period where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", id).query((rs, n) -> presenceView(rs)).single();
        outbox.publish(new PersonalDataChanged(support.tenant(), personId, v.entryDate(), List.of("vn_presence"), null,
                null, new PresencePeriod(v.id(), v.entryDate(), v.exitDate()), "PRESENCE_CHANGED"));
        return v;
    }

    private static void checkPresence(LocalDate entry, LocalDate exit) {
        if (entry == null) {
            throw ApiException.unprocessable("invalid-period", "The entry date is required");
        }
        if (exit != null && exit.isBefore(entry)) {
            throw ApiException.unprocessable("invalid-period", "The exit date cannot be before the entry date");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private ResidencyData validate(ResidencyData d) {
        if (d == null || d.status() == null || d.basis() == null || d.validFrom() == null) {
            throw ApiException.unprocessable("invalid-tax-residency", "Status, basis and validFrom are required");
        }
        boolean ok = ("RESIDENT".equals(d.status()) && RESIDENT_BASES.contains(d.basis()))
                || ("NON_RESIDENT".equals(d.status()) && NON_RESIDENT_BASES.contains(d.basis()));
        if (!ok) {
            throw ApiException.unprocessable("invalid-tax-residency-basis", "Basis " + d.basis()
                    + " does not apply to status " + d.status());
        }
        if (d.validTo() != null && !d.validTo().isAfter(d.validFrom())) {
            throw ApiException.unprocessable("invalid-period", "validTo (exclusive) must be after validFrom");
        }
        if (d.arrivalDate() != null && d.departureDate() != null && d.departureDate().isBefore(d.arrivalDate())) {
            throw ApiException.unprocessable("invalid-period", "The departure date cannot be before the arrival date");
        }
        if (d.daysInVn() != null && (d.daysInVn() < 0 || d.daysInVn() > 366)) {
            throw ApiException.unprocessable("invalid-days-in-vn", "Days in Vietnam must be between 0 and 366");
        }
        return d;
    }

    private void insert(UUID id, UUID personId, ResidencyData d, String source, String proposalRef) {
        db.jdbc().sql("""
                        insert into hr.tax_residency (tenant_id, id, person_id, status, basis, valid_from, valid_to,
                               arrival_date, departure_date, tax_year, days_in_vn, source, proposal_ref, approval_status,
                               created_by, updated_by)
                        values (:t, :id, :p, :s, :b, :from, :to, :arr, :dep, :year, :days, :src, :ref, 'PENDING_APPROVAL',
                                :a, :a)""")
                .param("t", support.tenant()).param("id", id).param("p", personId).param("s", d.status())
                .param("b", d.basis()).param("from", d.validFrom()).param("to", d.validTo())
                .param("arr", d.arrivalDate()).param("dep", d.departureDate())
                .param("year", d.taxYear() == null ? null : d.taxYear().shortValue())
                .param("days", d.daysInVn() == null ? null : d.daysInVn().shortValue()).param("src", source)
                .param("ref", proposalRef).param("a", support.actor())
                .update();
    }

    private ResidencyView owned(UUID personId, UUID id) {
        ResidencyView r = find(id);
        if (!r.personId().equals(personId)) {
            throw ApiException.notFound("Tax residency", id);
        }
        return r;
    }

    private ResidencyView find(UUID id) {
        return db.jdbc().sql("select * from hr.tax_residency where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", id).query((rs, n) -> view(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Tax residency", id));
    }

    private static ResidencyView view(ResultSet rs) throws SQLException {
        Object year = rs.getObject("tax_year");
        Object days = rs.getObject("days_in_vn");
        OffsetDateTime approvedAt = rs.getObject("approved_at", OffsetDateTime.class);
        return new ResidencyView(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getString("status"), rs.getString("basis"), rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class), rs.getObject("arrival_date", LocalDate.class),
                rs.getObject("departure_date", LocalDate.class), year == null ? null : ((Number) year).intValue(),
                days == null ? null : ((Number) days).intValue(), rs.getString("source"), rs.getString("proposal_ref"),
                rs.getString("approval_status"), rs.getObject("approved_by", UUID.class),
                approvedAt == null ? null : approvedAt.toInstant(), rs.getObject("evidence_document_id", UUID.class),
                rs.getInt("version"));
    }

    private static PresenceView presenceView(ResultSet rs) throws SQLException {
        return new PresenceView(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getObject("entry_date", LocalDate.class), rs.getObject("exit_date", LocalDate.class),
                rs.getObject("evidence_document_id", UUID.class), rs.getInt("version"));
    }
}
