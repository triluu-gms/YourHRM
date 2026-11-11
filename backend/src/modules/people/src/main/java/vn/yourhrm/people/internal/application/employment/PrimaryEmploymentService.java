package vn.yourhrm.people.internal.application.employment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.EmploymentEvents.PrimaryEmploymentChanged;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.family.DependantService;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.assignment.ProcessedMarkers;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore.Row;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartResult;

/**
 * PIT primary employment (BR-PPL-004, US-PPL-022/024; {@code hr.primary_employment}, Pattern A). Purpose is
 * {@code PIT_FAMILY_DEDUCTION} only (DDL CHECK; the SOCIAL_INSURANCE purpose of older texts is withdrawn, SKILL conflict
 * #1: insurance is per type in {@code hr.insurance_participation}).
 *
 * <p>Rules:
 * <ul>
 *   <li>a person with one live employment needs no row: that employment is primary by default;</li>
 *   <li>with 2+ live employments every day must be covered by exactly one ACTIVE designation: the DB excludes overlaps
 *       ({@code primary_employment_ex}); coverage is kept by this service (a concurrent hire designates the existing
 *       employment by default, a change splits the current row) and checked by the exception report (US-PPL-025);</li>
 *   <li>changes are approved through {@code hr.PRIMARY_EMPLOYMENT_CHANGE}, take effect from the first day of a month
 *       (the family deduction is monthly) and move the person's active dependant claims to the new primary
 *       ({@link DependantService#transferToNewPrimary}, US-PPL-024) → {@code people.PrimaryEmploymentChanged} (OUT-36);</li>
 *   <li>when the primary employment ends its designation ends with it; if one live employment remains it takes over
 *       (dependants transferred), if 2+ remain HR must re-designate (the exception report shows NO_PRIMARY).</li>
 * </ul>
 * A pending request is kept as a CANCELLED row whose {@code reason_code} is {@code REQUESTED:<reason>} (the DDL has no
 * pending status; CANCELLED rows are outside the exclusion constraint); on resolution it becomes
 * {@code APPLIED:<reason>} / {@code REJECTED:<reason>}.
 */
@Service
public class PrimaryEmploymentService {

    static final String REQUESTED = "REQUESTED:";

    /** One designation row. */
    public record Designation(UUID id, UUID employmentId, String employeeNo, UUID legalEntityId, LocalDate validFrom,
            LocalDate validTo, String status, String reasonCode, String source, int version) {
    }

    /**
     * The person's PIT primary employment on {@code asOf}.
     *
     * @param basis DESIGNATED (a row covers the day), SINGLE_EMPLOYMENT (one live employment, no row needed),
     *              MISSING (2+ live employments and no designation: to be fixed) or NONE (no live employment)
     */
    public record PrimaryView(UUID personId, LocalDate asOf, UUID primaryEmploymentId, String basis,
            int liveEmployments, List<Designation> designations, List<PendingRequest> pending) {
    }

    public record PendingRequest(UUID requestId, UUID employmentId, LocalDate validFrom, String reasonCode) {
    }

    public record ChangeRequest(UUID employmentId, LocalDate validFrom, String reasonCode, String comment) {
    }

    /** {@code status}: PENDING_APPROVAL or APPLIED. */
    public record ChangeResult(UUID requestId, String status, UUID workflowInstanceId, UUID designationId) {
    }

    private final HrDb db;
    private final EmploymentStore employments;
    private final EmployeeAccess access;
    private final DependantService dependants;
    private final WorkflowService workflow;
    private final ProcessedMarkers markers;
    private final HrOutbox outbox;
    private final AuditLog audit;
    private final BusinessClock clock;

    public PrimaryEmploymentService(HrDb db, EmploymentStore employments, EmployeeAccess access,
            DependantService dependants, WorkflowService workflow, ProcessedMarkers markers, HrOutbox outbox,
            AuditLog audit, BusinessClock clock) {
        this.db = db;
        this.employments = employments;
        this.access = access;
        this.dependants = dependants;
        this.workflow = workflow;
        this.markers = markers;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    // ------------------------------------------------------------------------------------------------------- reads

    @HrTransactional(readOnly = true)
    public PrimaryView view(UUID personId, LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        access.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, personId, d);
        return primaryOn(personId, d);
    }

    /** The primary employment on {@code day} without permission checks (internal use: reports, other streams). */
    @HrTransactional(readOnly = true)
    public PrimaryView primaryOn(UUID personId, LocalDate day) {
        List<Row> live = employments.ofPerson(personId).stream().filter(e -> e.covers(day)).toList();
        List<Designation> rows = designations(personId);
        Optional<Designation> covering = rows.stream().filter(r -> "ACTIVE".equals(r.status()) && covers(r, day))
                .findFirst();
        UUID primary;
        String basis;
        if (covering.isPresent()) {
            primary = covering.get().employmentId();
            basis = "DESIGNATED";
        } else if (live.size() == 1) {
            primary = live.getFirst().id();
            basis = "SINGLE_EMPLOYMENT";
        } else if (live.isEmpty()) {
            primary = null;
            basis = "NONE";
        } else {
            primary = null;
            basis = "MISSING";
        }
        List<PendingRequest> pending = rows.stream().filter(r -> r.reasonCode() != null
                        && r.reasonCode().startsWith(REQUESTED))
                .map(r -> new PendingRequest(r.id(), r.employmentId(), r.validFrom(),
                        r.reasonCode().substring(REQUESTED.length())))
                .toList();
        return new PrimaryView(personId, day, primary, basis, live.size(),
                rows.stream().filter(r -> "ACTIVE".equals(r.status())).toList(), pending);
    }

    public List<Designation> designations(UUID personId) {
        return db.jdbc().sql("""
                        select p.id, p.employment_id, e.employee_no, e.legal_entity_id, p.valid_from, p.valid_to,
                               p.status, p.reason_code, p.source, p.version
                          from hr.primary_employment p
                          join hr.employment e on e.tenant_id = p.tenant_id and e.id = p.employment_id
                         where p.tenant_id = :t and p.person_id = :p and p.purpose = 'PIT_FAMILY_DEDUCTION'
                         order by p.valid_from, p.id""")
                .param("t", tenant()).param("p", personId)
                .query((rs, n) -> new Designation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getInt(10)))
                .list();
    }

    // ----------------------------------------------------------------------------------------- change (approved)

    @HrTransactional
    public ChangeResult requestChange(UUID personId, ChangeRequest req) {
        if (req.employmentId() == null || req.validFrom() == null) {
            throw ApiException.unprocessable("invalid-request", "employmentId and validFrom are required");
        }
        access.require(PeoplePermissions.EMPLOYMENT_UPDATE, req.employmentId(), req.validFrom());
        employments.lockPerson(personId);
        Row target = employments.find(req.employmentId()).filter(e -> e.personId().equals(personId))
                .orElseThrow(() -> ApiException.notFound("Employment", req.employmentId()));
        validateChange(personId, target, req.validFrom());
        String reason = req.reasonCode() == null || req.reasonCode().isBlank() ? "EMPLOYEE_REGISTRATION"
                : req.reasonCode();
        UUID requestId = Ids.newId();
        db.jdbc().sql("""
                        insert into hr.primary_employment (tenant_id, id, person_id, employment_id, purpose, valid_from,
                               status, reason_code, source, created_by, updated_by)
                        values (:t, :id, :p, :e, 'PIT_FAMILY_DEDUCTION', :vf, 'CANCELLED', :r, 'APP', :a, :a)""")
                .param("t", tenant()).param("id", requestId).param("p", personId).param("e", target.id())
                .param("vf", req.validFrom()).param("r", REQUESTED + reason)
                .param("a", TenantContext.current().userAccountId()).update();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("employeeNo", target.employeeNo());
        summary.put("validFrom", req.validFrom().toString());
        summary.put("reasonCode", reason);
        if (req.comment() != null) {
            summary.put("comment", req.comment());
        }
        StartResult started = workflow.start(new StartRequest(EmploymentKeys.PRIMARY_CHANGE_PROCESS,
                EmploymentKeys.PRIMARY_SUBJECT, requestId, null, EmploymentKeys.PRIMARY_SUBJECT + ":" + requestId,
                null, personId, target.legalEntityId(), null, null, null, summary, Map.of("reasonCode", reason),
                Map.of()));
        audit.record(new BusinessAction("REQUEST_PRIMARY_EMPLOYMENT_CHANGE", "hr", "primary_employment", requestId,
                target.legalEntityId(), Map.of("employmentId", target.id().toString(), "validFrom",
                        req.validFrom().toString(), "workflowInstanceId", String.valueOf(started.instanceId()))));
        if ("APPROVED".equals(started.status())) {
            Optional<UUID> applied = resolve(requestId, "APPROVED");
            return new ChangeResult(requestId, "APPLIED", started.instanceId(), applied.orElse(null));
        }
        return new ChangeResult(requestId, "PENDING_APPROVAL", started.instanceId(), null);
    }

    private void validateChange(UUID personId, Row target, LocalDate d) {
        if (d.getDayOfMonth() != 1) {
            throw ApiException.unprocessable("primary-change-month-start", "The PIT family deduction is monthly: a "
                    + "primary employment change takes effect on the first day of a month");
        }
        if (!target.covers(d)) {
            throw ApiException.unprocessable("outside-employment", "Employment " + target.employeeNo()
                    + " is not live on " + d);
        }
        long live = employments.ofPerson(personId).stream().filter(e -> e.covers(d)).count();
        if (live < 2) {
            throw ApiException.unprocessable("primary-designation-not-needed", "The person has a single live "
                    + "employment on " + d + ": it is the primary employment by default");
        }
        PrimaryView current = primaryOn(personId, d);
        if (target.id().equals(current.primaryEmploymentId())) {
            throw ApiException.unprocessable("already-primary", "Employment " + target.employeeNo()
                    + " is already the primary employment on " + d);
        }
    }

    /** Applies (APPROVED) or closes a change request after its workflow; idempotent. Returns the new row id. */
    @HrTransactional
    public Optional<UUID> resolve(UUID requestId, String outcome) {
        markers.lock(EmploymentKeys.PRIMARY_APPROVAL_MARKER, requestId);
        if (markers.exists(EmploymentKeys.PRIMARY_APPROVAL_MARKER, requestId)) {
            return Optional.empty();
        }
        var req = db.jdbc().sql("""
                        select person_id, employment_id, valid_from, reason_code from hr.primary_employment
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", requestId)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getString(4)})
                .optional().orElseThrow(() -> ApiException.notFound("Primary employment request", requestId));
        UUID personId = (UUID) req[0];
        String reasonCode = ((String) req[3]).startsWith(REQUESTED)
                ? ((String) req[3]).substring(REQUESTED.length()) : (String) req[3];
        if (!"APPROVED".equals(outcome)) {
            setRequestReason(requestId, "REJECTED:" + reasonCode);
            markers.mark(EmploymentKeys.PRIMARY_APPROVAL_MARKER, requestId, "core.WorkflowCompleted", "SKIPPED");
            return Optional.empty();
        }
        employments.lockPerson(personId);
        Row target = employments.find((UUID) req[1]).orElseThrow();
        LocalDate d = (LocalDate) req[2];
        validateChange(personId, target, d);
        UUID id = designate(personId, target, d, reasonCode, null);
        setRequestReason(requestId, "APPLIED:" + reasonCode);
        markers.mark(EmploymentKeys.PRIMARY_APPROVAL_MARKER, requestId, "core.WorkflowCompleted", "PROCESSED");
        return Optional.of(id);
    }

    private void setRequestReason(UUID requestId, String reason) {
        db.jdbc().sql("""
                        update hr.primary_employment set reason_code = :r, version = version + 1, updated_at = now()
                         where tenant_id = :t and id = :id""")
                .param("r", reason).param("t", tenant()).param("id", requestId).update();
    }

    /**
     * Makes {@code target} the primary from {@code d}: the covering row is ended (or withdrawn when it starts that
     * day), the new row runs until the next designation or the end of {@code target}. Publishes OUT-36 and transfers
     * the dependants. Caller holds the person lock.
     */
    private UUID designate(UUID personId, Row target, LocalDate d, String reasonCode, UUID decisionId) {
        UUID previous = primaryOn(personId, d).primaryEmploymentId();
        List<Designation> rows = designations(personId).stream().filter(r -> "ACTIVE".equals(r.status())).toList();
        LocalDate until = target.endExclusive();
        for (Designation r : rows) {
            if (covers(r, d)) {
                if (r.validFrom().equals(d)) {
                    cancelRow(r.id());
                } else {
                    endRow(r.id(), d);
                }
                until = min(until, r.validTo());
            } else if (r.validFrom().isAfter(d)) {
                until = min(until, r.validFrom());
            }
        }
        UUID id = insertActive(personId, target.id(), d, until, reasonCode, decisionId);
        publish(target, previous, d, until, reasonCode, id);
        dependants.transferToNewPrimary(personId, target.id(), d.withDayOfMonth(1));
        audit.record(new BusinessAction("DESIGNATE_PRIMARY_EMPLOYMENT", "hr", "primary_employment", id,
                target.legalEntityId(), details("employmentId", target.id(), "validFrom", d, "validTo", until,
                        "reasonCode", reasonCode, "previousEmploymentId", previous)));
        return id;
    }

    // ------------------------------------------------------------------------------- lifecycle hooks (internal)

    /**
     * A concurrent employment {@code hired} was approved: from its hire date the person has 2+ live employments, so
     * the employment that was primary until then (the existing designation, else the only other live employment,
     * else the earliest hired) is designated by default from that day (reason CONCURRENT_HIRE). HR may change it
     * afterwards through an approved request. No-op when a designation already covers the day.
     */
    @HrTransactional
    public void onConcurrentHireApproved(Row hired) {
        employments.lockPerson(hired.personId());
        LocalDate d = hired.hireDate();
        List<Row> others = employments.ofPerson(hired.personId()).stream()
                .filter(e -> !e.id().equals(hired.id()) && e.covers(d)).toList();
        if (others.isEmpty()) {
            return;
        }
        if (designations(hired.personId()).stream().anyMatch(r -> "ACTIVE".equals(r.status()) && covers(r, d))) {
            return;
        }
        // The previous primary: the designation that ended last before d, else the earliest hired live employment.
        UUID lastDesignated = designations(hired.personId()).stream()
                .filter(r -> "ACTIVE".equals(r.status()) && r.validTo() != null && !r.validTo().isAfter(d))
                .reduce((a, b) -> b).map(Designation::employmentId).orElse(null);
        Row keep = others.stream().filter(e -> e.id().equals(lastDesignated)).findFirst().orElse(others.getFirst());
        LocalDate until = keep.endExclusive();
        for (Designation r : designations(hired.personId())) {
            if ("ACTIVE".equals(r.status()) && r.validFrom().isAfter(d)) {
                until = min(until, r.validFrom());
            }
        }
        UUID id = insertActive(hired.personId(), keep.id(), d, until, "CONCURRENT_HIRE", null);
        publish(keep, keep.id(), d, until, "CONCURRENT_HIRE", id);
    }

    /** The hire of {@code cancelled} was cancelled: its own rows and the default rows its hire created are withdrawn. */
    @HrTransactional
    public void onHireCancelled(Row cancelled) {
        employments.lockPerson(cancelled.personId());
        for (Designation r : designations(cancelled.personId())) {
            if (!"ACTIVE".equals(r.status())) {
                continue;
            }
            boolean own = r.employmentId().equals(cancelled.id());
            boolean defaultForHire = "CONCURRENT_HIRE".equals(r.reasonCode())
                    && r.validFrom().equals(cancelled.hireDate());
            if (own || defaultForHire) {
                cancelRow(r.id());
                Row e = employments.find(r.employmentId()).orElseThrow();
                outbox.publish(new PrimaryEmploymentChanged(tenant(), e.legalEntityId(), e.personId(), e.id(),
                        r.validFrom(), EmploymentKeys.PURPOSE_PIT, null, r.validFrom(), r.validFrom(),
                        "HIRE_CANCELLED", r.id()));
            }
        }
    }

    /**
     * Employment {@code employmentId} ends (its {@code end_date} is set): an ACTIVE designation of it is ended at the
     * day after; when exactly one live employment remains, it takes over (dependants transferred, OUT-36 with reason
     * PRIMARY_ENDED); with 2+ remaining HR must re-designate. Idempotent. For the separation stream and the daily job.
     *
     * @return true when a designation was ended
     */
    @HrTransactional
    public boolean onEmploymentEnded(UUID employmentId) {
        Row ended = employments.find(employmentId).orElseThrow(() -> ApiException.notFound("Employment",
                employmentId));
        if (ended.endDate() == null) {
            return false;
        }
        employments.lockPerson(ended.personId());
        LocalDate after = ended.endExclusive();
        boolean changed = false;
        for (Designation r : designations(ended.personId())) {
            if (!"ACTIVE".equals(r.status()) || !r.employmentId().equals(employmentId)) {
                continue;
            }
            if (!r.validFrom().isBefore(after)) {
                cancelRow(r.id());
                changed = true;
            } else if (r.validTo() == null || r.validTo().isAfter(after)) {
                endRow(r.id(), after);
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        List<Row> remaining = employments.ofPerson(ended.personId()).stream()
                .filter(e -> !e.id().equals(employmentId) && e.covers(after)).toList();
        if (remaining.size() == 1) {
            Row next = remaining.getFirst();
            outbox.publish(new PrimaryEmploymentChanged(tenant(), next.legalEntityId(), next.personId(), next.id(),
                    after, EmploymentKeys.PURPOSE_PIT, employmentId, after, next.endExclusive(), "PRIMARY_ENDED",
                    null));
            LocalDate month = after.getDayOfMonth() == 1 ? after : after.withDayOfMonth(1).plusMonths(1);
            dependants.transferToNewPrimary(ended.personId(), next.id(), month);
        }
        audit.record(new BusinessAction("END_PRIMARY_EMPLOYMENT", "hr", "primary_employment", employmentId,
                ended.legalEntityId(), details("endDate", ended.endDate(), "remainingLive", remaining.size())));
        return true;
    }

    /** Designations of employments with an end date that still run beyond it (daily job). */
    @HrTransactional
    public int closeEndedPrimaries() {
        List<UUID> ids = db.jdbc().sql("""
                        select distinct e.id from hr.primary_employment p
                          join hr.employment e on e.tenant_id = p.tenant_id and e.id = p.employment_id
                         where p.tenant_id = :t and p.status = 'ACTIVE' and e.end_date is not null
                           and (p.valid_to is null or p.valid_to > e.end_date + 1)""")
                .param("t", tenant()).query(UUID.class).list();
        int n = 0;
        for (UUID id : ids) {
            if (onEmploymentEnded(id)) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------------------

    private UUID insertActive(UUID personId, UUID employmentId, LocalDate from, LocalDate to, String reason,
            UUID decisionId) {
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into hr.primary_employment (tenant_id, id, person_id, employment_id, purpose,
                                   valid_from, valid_to, status, reason_code, decision_id, source, created_by, updated_by)
                            values (:t, :id, :p, :e, 'PIT_FAMILY_DEDUCTION', :vf, :vt, 'ACTIVE', :r, :d, 'APP', :a, :a)""")
                    .param("t", tenant()).param("id", id).param("p", personId).param("e", employmentId)
                    .param("vf", from).param("vt", to).param("r", reason).param("d", decisionId)
                    .param("a", TenantContext.current().userAccountId()).update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw ApiException.unprocessable("primary-employment-overlap", "Another primary employment designation "
                    + "covers part of this period (primary_employment_ex)");
        }
        return id;
    }

    private void endRow(UUID id, LocalDate validTo) {
        db.jdbc().sql("""
                        update hr.primary_employment set valid_to = :vt, version = version + 1, updated_at = now(),
                               updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("vt", validTo).param("a", TenantContext.current().userAccountId()).param("t", tenant())
                .param("id", id).update();
    }

    private void cancelRow(UUID id) {
        db.jdbc().sql("""
                        update hr.primary_employment set status = 'CANCELLED', version = version + 1, updated_at = now(),
                               updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("a", TenantContext.current().userAccountId()).param("t", tenant()).param("id", id).update();
    }

    private void publish(Row primary, UUID previous, LocalDate from, LocalDate to, String reason, UUID rowId) {
        outbox.publish(new PrimaryEmploymentChanged(tenant(), primary.legalEntityId(), primary.personId(),
                primary.id(), from, EmploymentKeys.PURPOSE_PIT, previous, from, to, reason, rowId));
    }

    static boolean covers(Designation r, LocalDate d) {
        return !d.isBefore(r.validFrom()) && (r.validTo() == null || d.isBefore(r.validTo()));
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    static Map<String, String> details(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(kv[i].toString(), kv[i + 1].toString());
            }
        }
        return m;
    }

    static List<String> list(String... values) {
        return new ArrayList<>(List.of(values));
    }
}
