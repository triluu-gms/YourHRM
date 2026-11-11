package vn.yourhrm.people.internal.application.ess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.family.SelfServiceScope;
import vn.yourhrm.people.internal.application.family.SelfServiceScope.Self;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * People ESS pages (EP-PPL-10, BR-PPL-120/123): "my profile" (US-PPL-072), "my employment" (US-PPL-073) and the
 * former employee's documents (US-PPL-077). The employee is always the caller (SELF scope through
 * {@link SelfServiceScope}, Core's user ↔ employment link), never a request parameter; everything is read-only
 * (self-service changes of personal data are Phase 2, BR-PPL-022). P3 identifiers are shown masked (last 4 only).
 *
 * <ul>
 *   <li>Profile and employment need {@code hr.ess.view} (role ESS, active employees).</li>
 *   <li>Documents (signed contracts, appendices, decisions) need {@code hr.contract.view} with SELF scope (roles ESS
 *       and FORMER_EMPLOYEE, see the proposed Core role templates); a TERMINATED employment stays visible for
 *       {@code core.former_employee_access_days} days after its termination date. Files are downloaded through Core's
 *       short-lived signed URLs ({@link Documents#downloadLink}), which re-check the document type's permission.</li>
 *   <li>The salary tab is filled only when {@code hr.ess.salary_history_visible} is on for the employment's legal
 *       entity; it shows the caller's own versions only (read through the masking view at level PII, own data by
 *       tenant policy) and each view is audited (BR-PPL-044).</li>
 * </ul>
 */
@Service
public class EssService {

    public record IdentityDoc(String docType, String docNoMasked, String issuingCountryCode, LocalDate issueDate,
            LocalDate expiryDate, String status) {
    }

    public record Address(String addressType, String countryCode, String provinceCode, String communeCode,
            String addressLine, LocalDate validFrom, LocalDate validTo) {
    }

    public record Contact(String contactType, String contactValue, boolean primary, boolean verified) {
    }

    public record Qualification(String qualificationLevelCode, String vocationalSkillLevelCode,
            String generalEducationCode, String fieldOfStudy, String institutionName, Integer graduationYear,
            boolean highest) {
    }

    public record TaxResidency(String status, String basis, LocalDate validFrom, LocalDate validTo,
            String approvalStatus) {
    }

    public record EmploymentRef(UUID employmentId, String employeeNo, UUID legalEntityId, String status,
            LocalDate hireDate, LocalDate endDate) {
    }

    /** US-PPL-072. {@code birthDate} is the caller's own (not masked). */
    public record MyProfile(UUID personId, String familyName, String middleName, String givenName, String fullName,
            String preferredName, LocalDate birthDate, String genderCode, String nationalityCode,
            String nationalIdType, String nationalIdMasked, String taxCodeMasked, String siNumberMasked,
            String hiCardNoMasked, List<IdentityDoc> identityDocuments, List<Address> addresses,
            List<Contact> contacts, List<Qualification> qualifications, List<TaxResidency> taxResidency,
            List<EmploymentRef> employments) {
    }

    public record Assignment(String assignmentType, UUID orgUnitId, UUID positionId, UUID jobTitleId, UUID gradeId,
            UUID managerEmploymentId, UUID locationId, String actionCode, String reasonCode, LocalDate validFrom,
            LocalDate validTo) {
    }

    /** A downloadable document of the employee: kind CONTRACT, APPENDIX or DECISION. */
    public record Document(String kind, UUID recordId, String number, String typeCode, LocalDate effectiveDate,
            LocalDate endDate, LocalDate signDate, String status, UUID documentId) {
    }

    public record Allowance(String componentCode, BigDecimal amount) {
    }

    public record Salary(String payBasis, String salaryKind, BigDecimal baseSalaryAmount, BigDecimal agreedNetAmount,
            BigDecimal siSalaryAmount, String currencyCode, List<Allowance> allowances, String actionCode,
            LocalDate validFrom, LocalDate validTo) {
    }

    /** US-PPL-073: one own employment. {@code salary} is null when the tenant policy hides it. */
    public record MyEmployment(EmploymentRef employment, List<Assignment> assignments, List<Document> contracts,
            List<Document> decisions, boolean salaryVisible, List<Salary> salary) {
    }

    private final HrDb db;
    private final SelfServiceScope self;
    private final Documents documents;
    private final Settings settings;
    private final AuditLog audit;
    private final BusinessClock clock;

    public EssService(HrDb db, SelfServiceScope self, Documents documents, Settings settings, AuditLog audit,
            BusinessClock clock) {
        this.db = db;
        this.self = self;
        this.documents = documents;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    // ------------------------------------------------------------------------------------------- US-PPL-072

    @HrTransactional(readOnly = true)
    public MyProfile profile() {
        Self me = self.require(PeoplePermissions.ESS_VIEW);
        UUID t = tenant();
        UUID p = me.personId();
        List<IdentityDoc> docs = db.jdbc().sql("""
                        select doc_type, doc_no_masked, issuing_country_code, issue_date, expiry_date, status
                          from hr.v_person_identity_doc_masked where tenant_id = :t and person_id = :p
                         order by issue_date desc nulls last""")
                .param("t", t).param("p", p)
                .query((rs, n) -> new IdentityDoc(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getString(6))).list();
        List<Address> addresses = db.jdbc().sql("""
                        select address_type, country_code, province_code, commune_code, address_line, valid_from, valid_to
                          from hr.person_address where tenant_id = :t and person_id = :p
                           and (valid_to is null or valid_to > :d) order by address_type, valid_from""")
                .param("t", t).param("p", p).param("d", clock.today())
                .query((rs, n) -> new Address(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class))).list();
        List<Contact> contacts = db.jdbc().sql("""
                        select contact_type, contact_value, is_primary, is_verified from hr.person_contact
                         where tenant_id = :t and person_id = :p order by contact_type, is_primary desc""")
                .param("t", t).param("p", p)
                .query((rs, n) -> new Contact(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)))
                .list();
        List<Qualification> qualifications = db.jdbc().sql("""
                        select qualification_level_code, vocational_skill_level_code, general_education_code,
                               field_of_study, institution_name, graduation_year, is_highest
                          from hr.education_qualification where tenant_id = :t and person_id = :p
                         order by is_highest desc, graduation_year desc nulls last""")
                .param("t", t).param("p", p)
                .query((rs, n) -> new Qualification(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, Integer.class), rs.getBoolean(7))).list();
        List<TaxResidency> residency = db.jdbc().sql("""
                        select status, basis, valid_from, valid_to, approval_status from hr.tax_residency
                         where tenant_id = :t and person_id = :p and approval_status = 'APPROVED'
                         order by valid_from desc""")
                .param("t", t).param("p", p)
                .query((rs, n) -> new TaxResidency(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalDate.class), rs.getString(5))).list();
        List<EmploymentRef> employments = employments(me.employmentIds());
        return db.jdbc().sql("""
                        select family_name, middle_name, given_name, full_name, preferred_name, birth_date, gender_code,
                               nationality_code, national_id_type,
                               '••••' || coalesce(national_id_last4, ''), '••••' || coalesce(tax_code_last4, ''),
                               '••••' || coalesce(si_number_last4, ''), '••••' || coalesce(hi_card_no_last4, '')
                          from hr.person where tenant_id = :t and id = :p""")
                .param("t", t).param("p", p)
                .query((rs, n) -> new MyProfile(p, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, LocalDate.class), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), docs,
                        addresses, contacts, qualifications, residency, employments))
                .single();
    }

    // ------------------------------------------------------------------------------------------- US-PPL-073

    @HrTransactional
    public List<MyEmployment> employment() {
        Self me = self.require(PeoplePermissions.ESS_VIEW);
        UUID t = tenant();
        List<MyEmployment> out = new ArrayList<>();
        for (EmploymentRef e : employments(me.employmentIds())) {
            List<Assignment> assignments = db.jdbc().sql("""
                            select assignment_type, org_unit_id, position_id, job_title_id, grade_id,
                                   manager_employment_id, location_id, action_code, reason_code, valid_from, valid_to
                              from hr.assignment where tenant_id = :t and employment_id = :e and superseded_at is null
                             order by valid_from desc, assignment_type""")
                    .param("t", t).param("e", e.employmentId())
                    .query((rs, n) -> new Assignment(rs.getString(1), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                            rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getString(8),
                            rs.getString(9), rs.getObject(10, LocalDate.class), rs.getObject(11, LocalDate.class)))
                    .list();
            List<Document> docs = documentsOf(List.of(e.employmentId()));
            boolean salaryVisible = settings.booleanValue(EssKeys.SALARY_HISTORY_VISIBLE,
                    new Settings.Scope(e.legalEntityId(), null), clock.today());
            List<Salary> salary = salaryVisible ? ownSalary(e) : null;
            out.add(new MyEmployment(e, assignments,
                    docs.stream().filter(d -> !"DECISION".equals(d.kind())).toList(),
                    docs.stream().filter(d -> "DECISION".equals(d.kind())).toList(), salaryVisible, salary));
        }
        return out;
    }

    /**
     * The caller's own salary versions (current knowledge). Own data shown by tenant policy: the pay masking view is
     * read at level PII for this transaction (the caller holds no {@code hr.compensation.view}); audited.
     */
    private List<Salary> ownSalary(EmploymentRef e) {
        UUID t = tenant();
        db.jdbc().sql("select set_config('app.mask_level', 'PII', true)").query((rs, n) -> 1).single();
        Map<UUID, List<Allowance>> allowances = new HashMap<>();
        db.jdbc().sql("""
                        select a.salary_record_id, a.component_code, a.amount from hr.v_salary_record_allowance_masked a
                          join hr.salary_record s on s.tenant_id = a.tenant_id and s.id = a.salary_record_id
                         where a.tenant_id = :t and s.employment_id = :e and s.superseded_at is null
                         order by a.component_code""")
                .param("t", t).param("e", e.employmentId())
                .query(rs -> {
                    allowances.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new Allowance(rs.getString(2), rs.getBigDecimal(3)));
                });
        List<Salary> out = db.jdbc().sql("""
                        select id, pay_basis, salary_kind, base_salary_amount, agreed_net_amount, si_salary_amount,
                               currency_code, action_code, valid_from, valid_to
                          from hr.v_salary_record_masked where tenant_id = :t and employment_id = :e
                           and superseded_at is null order by valid_from desc""")
                .param("t", t).param("e", e.employmentId())
                .query((rs, n) -> new Salary(rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7),
                        List.copyOf(allowances.getOrDefault(rs.getObject(1, UUID.class), List.of())), rs.getString(8),
                        rs.getObject(9, LocalDate.class), rs.getObject(10, LocalDate.class)))
                .list();
        db.jdbc().sql("select set_config('app.mask_level', 'PII_AND_PAY', true)").query((rs, n) -> 1).single();
        audit.record(new BusinessAction("VIEW_OWN_SALARY", "hr", "employment", e.employmentId(), e.legalEntityId(),
                Map.of("purpose", "ESS_SALARY_HISTORY", "versions", Integer.toString(out.size()))));
        return out;
    }

    // ------------------------------------------------------------------------------------------- US-PPL-073/077

    /** Signed contracts, appendices and decisions of the caller's visible employments (active or former). */
    @HrTransactional(readOnly = true)
    public List<Document> documents() {
        return documentsOf(visibleForDocuments());
    }

    /** A short-lived signed download URL of one of the caller's own documents (404 otherwise). */
    @HrTransactional(readOnly = true)
    public Documents.DownloadLink downloadLink(UUID documentId) {
        boolean own = documentsOf(visibleForDocuments()).stream().anyMatch(d -> documentId.equals(d.documentId()));
        if (!own) {
            throw ApiException.notFound("Document", documentId);
        }
        return documents.downloadLink(documentId, null);
    }

    /**
     * Own employments whose documents the caller may see with {@code hr.contract.view} (SELF): live ones, and
     * TERMINATED ones until {@code core.former_employee_access_days} days after the termination date (OD-8).
     */
    private List<UUID> visibleForDocuments() {
        Self me = self.require(PeoplePermissions.CONTRACT_VIEW);
        LocalDate today = clock.today();
        long days = settings.longValue(EssKeys.FORMER_EMPLOYEE_ACCESS_DAYS, Settings.Scope.tenant(), today);
        List<UUID> visible = employments(me.employmentIds()).stream()
                .filter(e -> !"CANCELLED".equals(e.status()))
                .filter(e -> !"TERMINATED".equals(e.status()) || e.endDate() == null
                        || !today.isAfter(e.endDate().plusDays(days)))
                .map(EmploymentRef::employmentId).toList();
        if (visible.isEmpty()) {
            throw ApiException.notFound("Employee record of the current user", "me");
        }
        return visible;
    }

    private List<Document> documentsOf(List<UUID> employmentIds) {
        UUID t = tenant();
        return db.jdbc().sql("""
                        select 'CONTRACT' as kind, c.id, c.contract_no, c.contract_type_code, c.start_date, c.end_date,
                               c.sign_date, c.status, c.document_id, 1 as ord
                          from hr.contract c
                         where c.tenant_id = :t and c.employment_id in (:ids)
                           and c.status in ('ACTIVE','SUSPENDED','EXPIRED','TERMINATED')
                        union all
                        select 'APPENDIX', a.id, a.appendix_no, null, a.effective_date, null, null, a.status,
                               a.document_id, 2
                          from hr.contract_appendix a join hr.contract c on c.tenant_id = a.tenant_id and c.id = a.contract_id
                         where a.tenant_id = :t and c.employment_id in (:ids) and a.status = 'SIGNED'
                        union all
                        select 'DECISION', d.id, d.decision_no, d.decision_type_code, d.effective_date, null, d.sign_date,
                               d.status, d.document_id, 3
                          from hr.decision d
                         where d.tenant_id = :t and d.employment_id in (:ids) and d.status in ('APPROVED','SIGNED')
                         order by 10, 5 desc""")
                .param("t", t).param("ids", employmentIds)
                .query((rs, n) -> new Document(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class), rs.getString(8), rs.getObject(9, UUID.class)))
                .list();
    }

    private List<EmploymentRef> employments(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.jdbc().sql("""
                        select id, employee_no, legal_entity_id, status, hire_date, end_date from hr.employment
                         where tenant_id = :t and id in (:ids) order by hire_date desc""")
                .param("t", tenant()).param("ids", ids)
                .query((rs, n) -> new EmploymentRef(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class)))
                .list();
    }
}
