package vn.yourhrm.people.internal.application.person;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;

/**
 * Work permits of foreign workers (BR-PPL-020, US-PPL-011; {@code hr.work_permit}, per employment, Pattern A with
 * {@code valid_to} EXCLUSIVE): work permit, exemption certificate or "not required" confirmation, with number, job
 * title and location on the permit, authority and validity. A WORK_PERMIT needs its end; one ACTIVE permit per
 * employment and day. A renewal points to the permit it extends ({@code renewal_of_id}). Statuses
 * DRAFT → APPLIED → ACTIVE → EXPIRED / REVOKED, or CANCELLED before activation. Coverage checks for assignments and
 * definite contracts are {@link #requireCoverage}; expiry alerts run in {@link PersonJobService}.
 * Decree 219/2025/ND-CP {@code [verify-legal]}.
 */
@Service
public class WorkPermitService {

    public static final Set<String> TYPES = Set.of("WORK_PERMIT", "EXEMPTION_CERT", "NOT_REQUIRED_CONFIRMATION");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("DRAFT", "APPLIED", "ACTIVE", "CANCELLED"),
            "APPLIED", Set.of("APPLIED", "ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("ACTIVE", "EXPIRED", "REVOKED"),
            "EXPIRED", Set.of("EXPIRED"),
            "REVOKED", Set.of("REVOKED"),
            "CANCELLED", Set.of("CANCELLED"));

    public record PermitData(UUID employmentId, String permitType, String permitNo, String jobTitleOnPermit,
            String workLocationText, String issuingAuthority, LocalDate issueDate, LocalDate validFrom,
            LocalDate validTo, UUID renewalOfId, String status) {
    }

    public record PermitView(UUID id, UUID personId, UUID employmentId, String permitType, String permitNo,
            String jobTitleOnPermit, String workLocationText, String issuingAuthority, LocalDate issueDate,
            LocalDate validFrom, LocalDate validTo, UUID renewalOfId, UUID scanDocumentId, String status, int version) {
    }

    /**
     * Result of a coverage check.
     *
     * @param required          false for a Vietnamese national (no permit needed)
     * @param coveredUntil      EXCLUSIVE end of the ACTIVE permit coverage (null = open-ended)
     * @param renewalInProgress a renewal of the last permit is recorded (APPLIED / ACTIVE)
     */
    public record Coverage(boolean required, LocalDate coveredUntil, boolean renewalInProgress) {
    }

    public record Expiring(UUID permitId, UUID personId, UUID employmentId, String employeeNo, String fullName,
            String permitType, LocalDate lastValidDay, int daysLeft, boolean renewalInProgress) {
    }

    private final HrDb db;
    private final PersonSupport support;
    private final EmployeeAccess access;

    public WorkPermitService(HrDb db, PersonSupport support, EmployeeAccess access) {
        this.db = db;
        this.support = support;
        this.access = access;
    }

    @HrTransactional(readOnly = true)
    public List<PermitView> list(UUID personId) {
        support.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, personId);
        return db.jdbc().sql("select * from hr.work_permit where tenant_id = :t and person_id = :p order by valid_from")
                .param("t", support.tenant()).param("p", personId).query((rs, n) -> view(rs)).list()
                .stream().filter(p -> access.can(PeoplePermissions.EMPLOYEE_VIEW, p.employmentId(), support.today()))
                .toList();
    }

    @HrTransactional
    public PermitView add(UUID personId, PermitData d) {
        if (d.employmentId() == null) {
            throw ApiException.unprocessable("invalid-employment", "A work permit belongs to an employment");
        }
        access.require(PeoplePermissions.WORK_PERMIT_UPDATE, d.employmentId(), support.today());
        support.lockPerson(personId);
        String status = d.status() == null ? "DRAFT" : d.status();
        if (!TRANSITIONS.get("DRAFT").contains(status)) {
            throw ApiException.unprocessable("invalid-status", "A new permit is DRAFT, APPLIED or ACTIVE");
        }
        validate(d, status);
        if (d.renewalOfId() != null) {
            UUID renewedEmployment = db.jdbc().sql("""
                            select employment_id from hr.work_permit where tenant_id = :t and id = :id and person_id = :p""")
                    .param("t", support.tenant()).param("id", d.renewalOfId()).param("p", personId)
                    .query(UUID.class).optional()
                    .orElseThrow(() -> ApiException.unprocessable("invalid-renewal", "The renewed permit does not exist"));
            if (!renewedEmployment.equals(d.employmentId())) {
                throw ApiException.unprocessable("invalid-renewal", "A renewal belongs to the same employment");
            }
        }
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into hr.work_permit (tenant_id, id, person_id, employment_id, permit_type, permit_no,
                                   job_title_on_permit, work_location_text, issuing_authority, issue_date, valid_from,
                                   valid_to, renewal_of_id, status, created_by, updated_by)
                            values (:t, :id, :p, :e, :type, :no, :job, :loc, :auth, :issued, :from, :to, :renewal,
                                    :status, :a, :a)""")
                    .param("t", support.tenant()).param("id", id).param("p", personId).param("e", d.employmentId())
                    .param("type", d.permitType()).param("no", trim(d.permitNo())).param("job", trim(d.jobTitleOnPermit()))
                    .param("loc", trim(d.workLocationText())).param("auth", trim(d.issuingAuthority()))
                    .param("issued", d.issueDate()).param("from", d.validFrom()).param("to", d.validTo())
                    .param("renewal", d.renewalOfId()).param("status", status).param("a", support.actor())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw constraint(e);
        }
        return find(id);
    }

    /** Replaces the permit's attributes and moves its status along the allowed transitions. */
    @HrTransactional
    public PermitView change(UUID personId, UUID id, int version, PermitData d) {
        PermitView current = owned(personId, id);
        access.require(PeoplePermissions.WORK_PERMIT_UPDATE, current.employmentId(), support.today());
        String status = d.status() == null ? current.status() : d.status();
        if (!TRANSITIONS.getOrDefault(current.status(), Set.of()).contains(status)) {
            throw ApiException.unprocessable("invalid-status-transition", "A " + current.status()
                    + " permit cannot become " + status);
        }
        PermitData merged = new PermitData(current.employmentId(), current.permitType(), d.permitNo(),
                d.jobTitleOnPermit(), d.workLocationText(), d.issuingAuthority(), d.issueDate(), d.validFrom(),
                d.validTo(), current.renewalOfId(), status);
        validate(merged, status);
        int updated;
        try {
            updated = db.jdbc().sql("""
                            update hr.work_permit set permit_no = :no, job_title_on_permit = :job,
                                   work_location_text = :loc, issuing_authority = :auth, issue_date = :issued,
                                   valid_from = :from, valid_to = :to, status = :status, version = version + 1,
                                   updated_by = :a
                             where tenant_id = :t and id = :id and version = :v""")
                    .param("no", trim(d.permitNo())).param("job", trim(d.jobTitleOnPermit()))
                    .param("loc", trim(d.workLocationText())).param("auth", trim(d.issuingAuthority()))
                    .param("issued", d.issueDate()).param("from", d.validFrom()).param("to", d.validTo())
                    .param("status", status).param("a", support.actor()).param("t", support.tenant()).param("id", id)
                    .param("v", version).update();
        } catch (DataIntegrityViolationException e) {
            throw constraint(e);
        }
        PersonSupport.requireUpdated(updated, "Work permit", id);
        return find(id);
    }

    @HrTransactional
    public PermitView attachScan(UUID personId, UUID id, UUID documentId) {
        PermitView current = owned(personId, id);
        access.require(PeoplePermissions.WORK_PERMIT_UPDATE, current.employmentId(), support.today());
        support.requireDocument(documentId, PersonKeys.DOC_WORK_PERMIT_SCAN, PersonKeys.OWNER_WORK_PERMIT, id);
        db.jdbc().sql("""
                        update hr.work_permit set document_id = :d, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("d", documentId).param("a", support.actor()).param("t", support.tenant()).param("id", id).update();
        return find(id);
    }

    /**
     * The permit coverage of an employment (BR-PPL-020): not required for a Vietnamese national; otherwise the end
     * of the chain of ACTIVE permits that starts with the one valid today (or the earliest future one), each
     * following permit starting no later than the previous one ends.
     */
    @HrTransactional(readOnly = true)
    public Coverage coverage(UUID employmentId) {
        UUID t = support.tenant();
        record Emp(UUID personId, String nationality) {
        }
        Emp emp = db.jdbc().sql("""
                        select e.person_id, p.nationality_code from hr.employment e
                          join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id
                         where e.tenant_id = :t and e.id = :id""")
                .param("t", t).param("id", employmentId)
                .query((rs, n) -> new Emp(rs.getObject(1, UUID.class), rs.getString(2))).optional()
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if ("VN".equals(emp.nationality())) {
            return new Coverage(false, null, false);
        }
        record Period(UUID id, LocalDate from, LocalDate to) {
        }
        List<Period> active = db.jdbc().sql("""
                        select id, valid_from, valid_to from hr.work_permit
                         where tenant_id = :t and employment_id = :e and status = 'ACTIVE'
                           and (valid_to is null or valid_to > :today) order by valid_from""")
                .param("t", t).param("e", employmentId).param("today", support.today())
                .query((rs, n) -> new Period(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class)))
                .list();
        if (active.isEmpty()) {
            return new Coverage(true, support.today(), renewalOf(t, employmentId, null));
        }
        Period last = active.getFirst();
        LocalDate end = last.to();
        for (Period p : active.subList(1, active.size())) {
            if (end == null || p.from().isAfter(end)) {
                break;
            }
            last = p;
            end = p.to() == null ? null : (p.to().isAfter(end) ? p.to() : end);
        }
        return new Coverage(true, end, end != null && renewalOf(t, employmentId, last.id()));
    }

    /**
     * Blocks an assignment end or a definite-contract end beyond the permit validity unless a renewal is recorded
     * (BR-PPL-020, US-PPL-011, US-PPL-050). {@code until} is the INCLUSIVE last day (null = open-ended).
     */
    @HrTransactional(readOnly = true)
    public void requireCoverage(UUID employmentId, LocalDate until) {
        Coverage c = coverage(employmentId);
        if (!c.required() || c.coveredUntil() == null) {
            return;
        }
        boolean covered = until != null && until.isBefore(c.coveredUntil());
        if (!covered && !c.renewalInProgress()) {
            throw ApiException.unprocessable("work-permit-coverage-exceeded", "The foreign worker's work permit is "
                    + "valid until " + c.coveredUntil().minusDays(1) + "; an end "
                    + (until == null ? "without date" : "on " + until) + " needs a recorded permit renewal (BR-PPL-020)",
                    Map.of("employmentId", employmentId, "lastValidDay", c.coveredUntil().minusDays(1).toString()));
        }
    }

    /** ACTIVE permits of employments in scope ending within {@code withinDays} (expiring list, US-PPL-011). */
    @HrTransactional(readOnly = true)
    public List<Expiring> expiring(int withinDays) {
        if (withinDays < 0 || withinDays > 3660) {
            throw ApiException.unprocessable("invalid-range", "withinDays must be between 0 and 3660");
        }
        LocalDate today = support.today();
        SqlFragment scope = access.scope(PeoplePermissions.EMPLOYEE_VIEW, today, "s");
        return db.jdbc().sql("select w.id, w.person_id, w.employment_id, e.employee_no, p.full_name, w.permit_type, "
                        + "w.valid_to, exists (select 1 from hr.work_permit r where r.tenant_id = w.tenant_id "
                        + "and r.renewal_of_id = w.id and r.status in ('APPLIED','ACTIVE')) as renewal from "
                        + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN
                        + " join hr.work_permit w on w.tenant_id = e.tenant_id and w.employment_id = e.id"
                        + " join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id"
                        + " where e.tenant_id = :t and w.status = 'ACTIVE' and w.valid_to is not null"
                        + " and w.valid_to > :today and w.valid_to <= :until and " + scope.sql()
                        + " order by w.valid_to")
                .param("t", support.tenant()).param("today", today).param("until", today.plusDays(withinDays + 1L))
                .param("asOf", today).params(scope.params())
                .query((rs, n) -> {
                    LocalDate last = rs.getObject(7, LocalDate.class).minusDays(1);
                    return new Expiring(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), last,
                            (int) ChronoUnit.DAYS.between(today, last), rs.getBoolean(8));
                })
                .list();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private boolean renewalOf(UUID t, UUID employmentId, UUID permitId) {
        return db.jdbc().sql("""
                        select exists (select 1 from hr.work_permit where tenant_id = :t and employment_id = :e
                                          and status in ('APPLIED','ACTIVE') and renewal_of_id is not null
                                          and (cast(:p as uuid) is null or renewal_of_id = :p))""")
                .param("t", t).param("e", employmentId).param("p", permitId).query(Boolean.class).single();
    }

    private static void validate(PermitData d, String status) {
        if (!TYPES.contains(d.permitType())) {
            throw ApiException.unprocessable("invalid-permit-type", "Permit type must be one of " + TYPES);
        }
        if (d.validFrom() == null) {
            throw ApiException.unprocessable("invalid-period", "validFrom is required");
        }
        if ("WORK_PERMIT".equals(d.permitType()) && d.validTo() == null) {
            throw ApiException.unprocessable("permit-end-required", "A work permit has a validity end (validTo)");
        }
        if (d.validTo() != null && !d.validTo().isAfter(d.validFrom())) {
            throw ApiException.unprocessable("invalid-period", "validTo (exclusive) must be after validFrom");
        }
        if (Set.of("ACTIVE", "EXPIRED", "REVOKED").contains(status) && !"NOT_REQUIRED_CONFIRMATION".equals(d.permitType())
                && (d.permitNo() == null || d.permitNo().isBlank())) {
            throw ApiException.unprocessable("permit-no-required", "An active permit or certificate has its number");
        }
    }

    private static ApiException constraint(DataIntegrityViolationException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains("work_permit_period_ex")) {
            return ApiException.conflict("work-permit-overlap", "Another active permit of this employment covers part "
                    + "of the period");
        }
        if (msg.contains("work_permit_employment_fk")) {
            return ApiException.unprocessable("invalid-employment", "The employment does not belong to this person");
        }
        return ApiException.conflict("work-permit-conflict", "The permit conflicts with existing data");
    }

    private PermitView owned(UUID personId, UUID id) {
        return db.jdbc().sql("select * from hr.work_permit where tenant_id = :t and id = :id and person_id = :p")
                .param("t", support.tenant()).param("id", id).param("p", personId)
                .query((rs, n) -> view(rs)).optional().orElseThrow(() -> ApiException.notFound("Work permit", id));
    }

    private PermitView find(UUID id) {
        return db.jdbc().sql("select * from hr.work_permit where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", id).query((rs, n) -> view(rs)).single();
    }

    Optional<PermitView> findOptional(UUID id) {
        return db.jdbc().sql("select * from hr.work_permit where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", id).query((rs, n) -> view(rs)).optional();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static PermitView view(ResultSet rs) throws SQLException {
        return new PermitView(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getObject("employment_id", UUID.class), rs.getString("permit_type"), rs.getString("permit_no"),
                rs.getString("job_title_on_permit"), rs.getString("work_location_text"),
                rs.getString("issuing_authority"), rs.getObject("issue_date", LocalDate.class),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                rs.getObject("renewal_of_id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getString("status"), rs.getInt("version"));
    }
}
