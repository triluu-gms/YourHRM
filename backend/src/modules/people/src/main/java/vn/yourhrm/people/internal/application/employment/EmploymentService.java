package vn.yourhrm.people.internal.application.employment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore.Row;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Employment records (BR-PPL-003, US-PPL-017): one per legal entity and period, employee number from Core's
 * {@code EMPLOYEE_NO} series (unique in the tenant), hire / original-hire / seniority dates, worker category
 * (collaborators included, Q7 assumed yes), employee category and contractual working regime.
 *
 * <p>Status machine (DDL CHECK): PRE_HIRE → ACTIVE (daily job on the hire date, approved hires only) → SUSPENDED
 * (contract stream's suspension flow) → TERMINATED (separation stream); PRE_HIRE → CANCELLED (no-show). The DDL has no
 * NOTICE status (SKILL conflict #3): {@code noticeState} is derived from an APPROVED / SCHEDULED separation.
 * "Incomplete" (US-PPL-018) is derived too — there is no column for it: {@code missingData} lists the mandatory items
 * still missing (identity, birth date, tax code, signed contract, salary bank account).
 */
@Service
public class EmploymentService {

    /** An employment as shown on the profile. P3 values never appear. */
    public record EmploymentView(UUID id, UUID personId, String fullName, UUID legalEntityId, String employeeNo,
            String hireType, LocalDate hireDate, LocalDate originalHireDate, LocalDate seniorityDate,
            LocalDate probationEndDate, LocalDate endDate, LocalDate lastWorkingDate, String status,
            String noticeState, String workerCategoryCode, String employeeCategoryCode, String workingRegimeCode,
            Boolean rehireEligible, UUID previousEmploymentId, String source, String hireApproval,
            UUID hireDecisionId, String hireDecisionNo, boolean incomplete, List<String> missingData,
            boolean pitPrimary, int version) {
    }

    /**
     * Status of an employment on {@code asOf}: {@code status} is the stored current status, {@code statusOn} the one
     * derived for the date (PRE_HIRE before the hire date, TERMINATED after the end date).
     */
    public record StatusView(UUID employmentId, LocalDate asOf, String status, String statusOn, String noticeState,
            LocalDate hireDate, LocalDate endDate, LocalDate lastWorkingDate, boolean live) {
    }

    /** PUT of HR-maintained attributes (hire date and legal entity are not editable: cancel and re-hire). */
    public record Attributes(String workerCategoryCode, String employeeCategoryCode, String workingRegimeCode,
            LocalDate originalHireDate, LocalDate seniorityDate, LocalDate probationEndDate) {
    }

    private final HrDb db;
    private final EmploymentStore store;
    private final EmployeeAccess access;
    private final PrimaryEmploymentService primaries;
    private final AuditLog audit;
    private final BusinessClock clock;

    public EmploymentService(HrDb db, EmploymentStore store, EmployeeAccess access, PrimaryEmploymentService primaries,
            AuditLog audit, BusinessClock clock) {
        this.db = db;
        this.store = store;
        this.access = access;
        this.primaries = primaries;
        this.audit = audit;
        this.clock = clock;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    @HrTransactional(readOnly = true)
    public EmploymentView get(UUID employmentId) {
        access.require(PeoplePermissions.EMPLOYEE_VIEW, employmentId, clock.today());
        return view(store.find(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId)));
    }

    /** The view without a permission check, for flows that already authorised their caller (and other streams). */
    @HrTransactional(readOnly = true)
    public EmploymentView describe(UUID employmentId) {
        return view(store.find(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId)));
    }

    /** Employments of a person in the caller's scope (others are left out), newest first. */
    @HrTransactional(readOnly = true)
    public List<EmploymentView> ofPerson(UUID personId) {
        LocalDate today = clock.today();
        access.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, personId, today);
        List<EmploymentView> out = new ArrayList<>();
        List<Row> rows = new ArrayList<>(store.ofPerson(personId));
        java.util.Collections.reverse(rows);
        for (Row r : rows) {
            if (access.can(PeoplePermissions.EMPLOYEE_VIEW, r.id(), today)) {
                out.add(view(r));
            }
        }
        return out;
    }

    @HrTransactional(readOnly = true)
    public StatusView status(UUID employmentId, LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        access.require(PeoplePermissions.EMPLOYEE_VIEW, employmentId, d);
        Row r = store.find(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        String on;
        if ("CANCELLED".equals(r.status())) {
            on = "CANCELLED";
        } else if (d.isBefore(r.hireDate())) {
            on = "PRE_HIRE";
        } else if (r.endDate() != null && d.isAfter(r.endDate())) {
            on = "TERMINATED";
        } else if ("PRE_HIRE".equals(r.status()) || "TERMINATED".equals(r.status())) {
            on = r.status();
        } else {
            on = suspendedOn(r.id(), d) ? "SUSPENDED" : "ACTIVE";
        }
        return new StatusView(r.id(), d, r.status(), on, noticeState(r.id()), r.hireDate(), r.endDate(),
                r.lastWorkingDate(), r.live());
    }

    @HrTransactional
    public EmploymentView update(UUID employmentId, int version, Attributes a) {
        access.require(PeoplePermissions.EMPLOYMENT_UPDATE, employmentId, clock.today());
        Row r = store.find(employmentId).orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (!r.live()) {
            throw ApiException.unprocessable("employment-not-live", "Employment " + r.employeeNo() + " is "
                    + r.status());
        }
        if (a.workerCategoryCode() == null || a.workerCategoryCode().isBlank()) {
            throw ApiException.unprocessable("worker-category-required", "workerCategoryCode is required");
        }
        if (a.originalHireDate() != null && a.originalHireDate().isAfter(r.hireDate())) {
            throw ApiException.unprocessable("invalid-original-hire-date",
                    "The original hire date cannot be after the hire date");
        }
        if (a.probationEndDate() != null && a.probationEndDate().isBefore(r.hireDate())) {
            throw ApiException.unprocessable("invalid-probation-end", "The probation end is before the hire date");
        }
        if (!store.updateAttributes(employmentId, version, a.workerCategoryCode(), a.employeeCategoryCode(),
                a.workingRegimeCode(), a.originalHireDate(), a.seniorityDate(), a.probationEndDate(),
                TenantContext.current().userAccountId())) {
            throw ApiException.conflict("stale-version", "Employment " + r.employeeNo() + " was changed (version "
                    + r.version() + "); reload and retry");
        }
        audit.record(new BusinessAction("UPDATE_EMPLOYMENT", "hr", "employment", employmentId, r.legalEntityId(),
                PrimaryEmploymentService.details("workerCategory", a.workerCategoryCode(), "employeeCategory",
                        a.employeeCategoryCode(), "workingRegime", a.workingRegimeCode(), "originalHireDate",
                        a.originalHireDate(), "seniorityDate", a.seniorityDate())));
        return view(store.find(employmentId).orElseThrow());
    }

    // ------------------------------------------------------------------------------------------- internal helpers

    EmploymentView view(Row r) {
        var decision = HireDecisions.of(db, r.id());
        List<String> missing = missingData(r);
        String fullName = db.jdbc().sql("select full_name from hr.person where tenant_id = :t and id = :p")
                .param("t", tenant()).param("p", r.personId()).query(String.class).optional().orElse(null);
        boolean primary = r.id().equals(primaries.primaryOn(r.personId(), clock.today()).primaryEmploymentId());
        return new EmploymentView(r.id(), r.personId(), fullName, r.legalEntityId(), r.employeeNo(), r.hireType(),
                r.hireDate(), r.originalHireDate(), r.seniorityDate(), r.probationEndDate(), r.endDate(),
                r.lastWorkingDate(), r.status(), noticeState(r.id()), r.workerCategoryCode(),
                r.employeeCategoryCode(), r.workingRegimeCode(), r.rehireEligible(), r.previousEmploymentId(),
                r.source(), HireDecisions.approvalState(db, r.id()),
                decision.map(HireDecisions.HireDecision::id).orElse(null),
                decision.map(HireDecisions.HireDecision::decisionNo).orElse(null), !missing.isEmpty() && r.live(),
                missing, primary, r.version());
    }

    /**
     * Mandatory hire data still missing (US-PPL-018 "incomplete"): NATIONAL_ID, BIRTH_DATE, TAX_CODE (a 12-digit CCCD
     * is the personal tax code since Circular 86/2024, so it counts), SIGNED_CONTRACT, BANK_ACCOUNT (an ACTIVE salary
     * account). The SI number is not required at hire (it is issued after the first declaration).
     */
    public List<String> missingData(Row r) {
        Map<String, Object> m = db.jdbc().sql("""
                        select p.national_id_bidx is not null as nid, p.birth_date is not null as birth,
                               (p.tax_code_bidx is not null or p.national_id_type = 'CCCD') as tax,
                               exists (select 1 from hr.contract c where c.tenant_id = p.tenant_id
                                          and c.employment_id = :e and c.status = 'ACTIVE' and c.sign_date is not null)
                                   as contract,
                               exists (select 1 from hr.bank_account b where b.tenant_id = p.tenant_id
                                          and b.person_id = p.id and b.status = 'ACTIVE'
                                          and (b.valid_to is null or b.valid_to > :d)) as bank
                          from hr.person p where p.tenant_id = :t and p.id = :p""")
                .param("t", tenant()).param("p", r.personId()).param("e", r.id()).param("d", clock.today())
                .query().singleRow();
        List<String> out = new ArrayList<>();
        if (!Boolean.TRUE.equals(m.get("nid"))) {
            out.add("NATIONAL_ID");
        }
        if (!Boolean.TRUE.equals(m.get("birth"))) {
            out.add("BIRTH_DATE");
        }
        if (!Boolean.TRUE.equals(m.get("tax"))) {
            out.add("TAX_CODE");
        }
        if (!Boolean.TRUE.equals(m.get("contract"))) {
            out.add("SIGNED_CONTRACT");
        }
        if (!Boolean.TRUE.equals(m.get("bank"))) {
            out.add("BANK_ACCOUNT");
        }
        return out;
    }

    /** NOTICE while a separation is APPROVED / SCHEDULED, SEPARATION_REQUESTED while SUBMITTED, else null. */
    String noticeState(UUID employmentId) {
        return db.jdbc().sql("""
                        select case when status in ('APPROVED','SCHEDULED') then 'NOTICE'
                                    when status = 'SUBMITTED' then 'SEPARATION_REQUESTED' end
                          from hr.separation where tenant_id = :t and employment_id = :e
                           and status in ('SUBMITTED','APPROVED','SCHEDULED')
                         order by created_at desc limit 1""")
                .param("t", tenant()).param("e", employmentId).query(String.class).optional().orElse(null);
    }

    private boolean suspendedOn(UUID employmentId, LocalDate d) {
        return db.jdbc().sql("""
                        select exists (select 1 from hr.contract_suspension s
                                        where s.tenant_id = :t and s.employment_id = :e
                                          and s.status in ('ACTIVE','APPROVED','ENDED')
                                          and s.valid_during @> cast(:d as date))""")
                .param("t", tenant()).param("e", employmentId).param("d", d).query(Boolean.class).single();
    }
}
