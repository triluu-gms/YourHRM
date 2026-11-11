package vn.yourhrm.people.internal.application.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.DocumentGeneration;
import vn.yourhrm.core.api.DocumentGeneration.GenerateRequest;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.core.api.JobCatalogue;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.HrMasking;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.SensitiveDataAccess;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The electronic labour management book (sổ quản lý lao động, BR-PPL-140, US-PPL-068) of a legal entity as of any date,
 * with the fields of Decree 145/2020 Art. 3 [verify-legal]: identity, residence, qualification, job, contract, salary,
 * social insurance, training and discipline (placeholders: Phase 2), start and termination. The employee list starts
 * from {@code hr.v_labour_book} (identity, qualification); every dated column is read from the base tables for the
 * requested date (the view is a current-date snapshot).
 *
 * <p>Protection: rows follow the caller's scope of {@code hr.statutory_report.view}; the national id is shown masked
 * on screen and decrypted only in an export, only for holders of {@code hr.person.view_sensitive}, each read audited;
 * salary columns only where the caller holds {@code hr.compensation.view} for the employee, read through the masking
 * view (fail closed) and audited (BR-PPL-044).
 */
@Service
public class LabourBookService {

    /** One line of the book. {@code nationalId}: masked unless revealed in an export. */
    public record Entry(UUID employmentId, String employeeNo, String fullName, String genderCode, LocalDate birthDate,
            String nationalityCode, String nationalIdType, String nationalId, String residenceAddress,
            String residenceProvinceCode, String residenceCommuneCode, String qualificationLevelCode,
            String vocationalSkillLevelCode, UUID orgUnitId, UUID positionId, UUID jobTitleId, String jobTitle,
            String contractTypeCode, String contractNo, LocalDate contractStartDate, LocalDate contractEndDate,
            Salary salary, boolean siParticipating, List<String> training, List<String> discipline,
            LocalDate hireDate, String employmentStatus, LocalDate terminationDate, String separationType,
            String legalGroundCode) {
    }

    /** The agreed salary in force on the book date (P2), present only for holders of the compensation view. */
    public record Salary(String payBasis, String salaryKind, BigDecimal baseAmount, String currencyCode,
            LocalDate validFrom) {
    }

    public record Book(UUID legalEntityId, LocalDate asOf, boolean identityRevealed, int salaryColumns,
            List<Entry> entries, List<String> notes) {
    }

    /** An exported file. */
    public record Export(String fileName, String mediaType, byte[] content, int rows) {
    }

    private record Raw(UUID employmentId, UUID personId, String employeeNo, String fullName, String gender,
            LocalDate birthDate, String nationality, String idType, String idLast4, String province, String commune,
            String addressLine, String qualification, String skill, UUID orgUnit, UUID position, UUID jobTitle,
            String contractType, String contractNo, LocalDate contractStart, LocalDate contractEnd, boolean si,
            LocalDate hireDate, LocalDate endDate, String status, LocalDate sepDate, String sepType, String ground) {
    }

    private final HrDb db;
    private final EmployeeAccess access;
    private final AuthorizationService authz;
    private final HrMasking masking;
    private final SensitiveDataAccess sensitive;
    private final PiiCipher cipher;
    private final JobCatalogue jobs;
    private final DocumentGeneration generation;
    private final AuditLog audit;
    private final BusinessClock clock;

    public LabourBookService(HrDb db, EmployeeAccess access, AuthorizationService authz, HrMasking masking,
            SensitiveDataAccess sensitive, PiiCipher cipher, JobCatalogue jobs, DocumentGeneration generation,
            AuditLog audit, BusinessClock clock) {
        this.db = db;
        this.access = access;
        this.authz = authz;
        this.masking = masking;
        this.sensitive = sensitive;
        this.cipher = cipher;
        this.jobs = jobs;
        this.generation = generation;
        this.audit = audit;
        this.clock = clock;
    }

    /** On-screen book (national id masked). */
    @HrTransactional
    public Book book(UUID legalEntityId, LocalDate asOf) {
        return build(legalEntityId, asOf == null ? clock.today() : asOf, PeoplePermissions.STATUTORY_REPORT_VIEW,
                false, null);
    }

    /** Excel export of the book (national id decrypted for holders of the sensitive view; audited). */
    @HrTransactional
    public Export excel(UUID legalEntityId, LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        Book book = build(legalEntityId, d, PeoplePermissions.STATUTORY_REPORT_EXECUTE, true, null);
        audit.record(new BusinessAction("EXPORT_LABOUR_BOOK", "hr", "legal_entity", legalEntityId, legalEntityId,
                Map.of("asOf", d.toString(), "rows", String.valueOf(book.entries().size()),
                        "identityRevealed", String.valueOf(book.identityRevealed()))));
        return new Export("so-quan-ly-lao-dong_" + d + ".xlsx", ExcelSheet.MEDIA_TYPE, excel(book),
                book.entries().size());
    }

    /** The book as an Excel file (used by the statutory register). */
    byte[] excel(Book book) {
        List<String> headers = List.of("STT", "Mã NV", "Họ và tên", "Giới tính", "Ngày sinh", "Quốc tịch",
                "Loại giấy tờ", "Số CCCD / hộ chiếu", "Nơi cư trú", "Trình độ chuyên môn kỹ thuật",
                "Bậc trình độ kỹ năng nghề", "Vị trí việc làm", "Loại hợp đồng", "Số hợp đồng", "Ngày bắt đầu HĐ",
                "Ngày kết thúc HĐ", "Hình thức trả lương", "Mức lương", "Tham gia BHXH", "Học nghề, đào tạo",
                "Kỷ luật, trách nhiệm vật chất", "Ngày bắt đầu làm việc", "Ngày chấm dứt", "Lý do chấm dứt");
        List<List<Object>> rows = new ArrayList<>();
        int i = 1;
        for (Entry e : book.entries()) {
            List<Object> r = new ArrayList<>();
            r.add(i++);
            r.add(e.employeeNo());
            r.add(e.fullName());
            r.add(e.genderCode());
            r.add(e.birthDate());
            r.add(e.nationalityCode());
            r.add(e.nationalIdType());
            r.add(e.nationalId());
            r.add(e.residenceAddress());
            r.add(e.qualificationLevelCode());
            r.add(e.vocationalSkillLevelCode());
            r.add(e.jobTitle());
            r.add(e.contractTypeCode());
            r.add(e.contractNo());
            r.add(e.contractStartDate());
            r.add(e.contractEndDate());
            r.add(e.salary() == null ? null : e.salary().payBasis());
            r.add(e.salary() == null ? null : e.salary().baseAmount());
            r.add(e.siParticipating());
            r.add(String.join("; ", e.training()));
            r.add(String.join("; ", e.discipline()));
            r.add(e.hireDate());
            r.add(e.terminationDate());
            r.add(e.legalGroundCode());
            rows.add(r);
        }
        return ExcelSheet.write("So quan ly lao dong", List.of("SỔ QUẢN LÝ LAO ĐỘNG",
                "Pháp nhân: " + book.legalEntityId(), "Tại ngày: " + ExcelSheet.date(book.asOf()),
                "Căn cứ: Nghị định 145/2020/NĐ-CP, Điều 3"), headers, rows);
    }

    /**
     * The PDF entry of one employee from the tenant's Core template {@code HR_LABOUR_BOOK_ENTRY} (PDF/A, Vietnamese
     * fonts), stored in Core's document service under the employment.
     */
    @HrTransactional
    public Generated entryPdf(UUID employmentId, LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        UUID le = db.jdbc().sql("select legal_entity_id from hr.employment where tenant_id = :t and id = :e")
                .param("t", tenant()).param("e", employmentId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        access.require(PeoplePermissions.STATUTORY_REPORT_EXECUTE, employmentId, d);
        Book book = build(le, d, PeoplePermissions.STATUTORY_REPORT_EXECUTE, true, employmentId);
        Entry e = book.entries().stream().findFirst().orElseThrow(() -> ApiException.unprocessable(
                "not-in-labour-book", "The employment is not in the labour book on " + d + " (not started or cancelled)"));
        Map<String, Object> f = new HashMap<>();
        f.put("book.as_of", ExcelSheet.date(d));
        f.put("employee.no", nz(e.employeeNo()));
        f.put("employee.full_name", nz(e.fullName()));
        f.put("employee.gender", "F".equals(e.genderCode()) ? "Nữ" : "M".equals(e.genderCode()) ? "Nam" : nz(e.genderCode()));
        f.put("employee.birth_date", ExcelSheet.date(e.birthDate()));
        f.put("employee.nationality", nz(e.nationalityCode()));
        f.put("employee.national_id", nz(e.nationalId()));
        f.put("employee.residence", nz(e.residenceAddress()));
        f.put("qualification.level", nz(e.qualificationLevelCode()));
        f.put("qualification.skill", nz(e.vocationalSkillLevelCode()));
        f.put("job.title", nz(e.jobTitle()));
        f.put("contract.type", nz(e.contractTypeCode()));
        f.put("contract.no", nz(e.contractNo()));
        f.put("contract.start", ExcelSheet.date(e.contractStartDate()));
        f.put("contract.end", ExcelSheet.date(e.contractEndDate()));
        f.put("salary.basis", e.salary() == null ? "" : e.salary().payBasis());
        f.put("salary.amount", e.salary() == null || e.salary().baseAmount() == null ? ""
                : NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"))
                        .format(e.salary().baseAmount().setScale(0, RoundingMode.HALF_UP)));
        f.put("si.participating", e.siParticipating() ? "Có" : "Không");
        f.put("training", e.training().isEmpty() ? "—" : String.join("; ", e.training()));
        f.put("discipline", e.discipline().isEmpty() ? "—" : String.join("; ", e.discipline()));
        f.put("employment.hire_date", ExcelSheet.date(e.hireDate()));
        f.put("termination.date", ExcelSheet.date(e.terminationDate()));
        f.put("termination.ground", nz(e.legalGroundCode()));
        UUID person = db.jdbc().sql("select person_id from hr.employment where tenant_id = :t and id = :e")
                .param("t", tenant()).param("e", employmentId).query(UUID.class).single();
        audit.record(new BusinessAction("EXPORT_LABOUR_BOOK_ENTRY", "hr", "employment", employmentId, le,
                Map.of("asOf", d.toString(), "identityRevealed", String.valueOf(book.identityRevealed()))));
        return generation.generate(new GenerateRequest(ReportKeys.TEMPLATE_LABOUR_BOOK_ENTRY, le, employmentId, person,
                "Sổ quản lý lao động - " + e.employeeNo(), d, f));
    }

    // ---------------------------------------------------------------------------------------------------------

    Book build(UUID legalEntityId, LocalDate asOf, String permission, boolean export, UUID onlyEmployment) {
        if (legalEntityId == null) {
            throw ApiException.unprocessable("legal-entity-required", "The labour book is kept per legal entity");
        }
        UUID t = tenant();
        var scope = access.scope(permission, asOf, "s");
        List<Raw> raws = db.jdbc().sql("select v.employment_id, v.person_id, v.employee_no, v.full_name, v.gender_code, "
                        + "v.birth_date, v.nationality_code, v.national_id_type, v.national_id_last4, "
                        + "addr.province_code, addr.commune_code, addr.address_line, v.highest_qualification_code, "
                        + "v.vocational_skill_level_code, asg.org_unit_id, asg.position_id, asg.job_title_id, "
                        + "ctr.contract_type_code, ctr.contract_no, ctr.start_date, ctr.end_date, "
                        + "exists (select 1 from hr.insurance_participation ip where ip.tenant_id = e.tenant_id "
                        + "  and ip.employment_id = e.id and ip.insurance_type = 'SI' and ip.status = 'ACTIVE' "
                        + "  and ip.is_participating and ip.valid_during @> cast(:asOf as date)) as si, "
                        + "e.hire_date, e.end_date, e.status, sep.termination_date, sep.separation_type, "
                        + "sep.legal_ground_code from " + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN + """
                         join hr.v_labour_book v on v.tenant_id = e.tenant_id and v.employment_id = e.id
                         left join lateral (select a.province_code, a.commune_code, a.address_line
                                              from hr.person_address a
                                             where a.tenant_id = e.tenant_id and a.person_id = e.person_id
                                               and a.address_type in ('PERMANENT','CURRENT')
                                               and a.valid_during @> cast(:asOf as date)
                                             order by (a.address_type = 'PERMANENT') desc limit 1) addr on true
                         left join lateral (select x.org_unit_id, x.position_id, x.job_title_id from hr.assignment x
                                             where x.tenant_id = e.tenant_id and x.employment_id = e.id
                                               and x.assignment_type = 'PRIMARY' and x.superseded_at is null
                                               and x.valid_from <= :asOf
                                             order by (x.valid_during @> cast(:asOf as date)) desc, x.valid_from desc
                                             limit 1) asg on true
                         left join lateral (select c.contract_type_code, c.contract_no, c.start_date, c.end_date
                                              from hr.contract c
                                             where c.tenant_id = e.tenant_id and c.employment_id = e.id
                                               and c.status in ('ACTIVE','SUSPENDED','EXPIRED','TERMINATED')
                                               and c.start_date <= :asOf
                                             order by (c.end_date is null or c.end_date >= :asOf) desc, c.start_date desc
                                             limit 1) ctr on true
                         left join lateral (select s.termination_date, s.separation_type, s.legal_ground_code
                                              from hr.separation s
                                             where s.tenant_id = e.tenant_id and s.employment_id = e.id
                                               and s.status = 'COMPLETED' and s.termination_date < :asOf
                                             order by s.termination_date desc limit 1) sep on true
                        where e.tenant_id = :t and e.legal_entity_id = :le and e.hire_date <= :asOf
                          and e.status not in ('CANCELLED','PRE_HIRE')
                          and (cast(:only as uuid) is null or e.id = :only)
                          and""" + " " + scope.sql() + " order by e.hire_date, e.employee_no")
                .param("t", t).param("le", legalEntityId).param("asOf", asOf).param("only", onlyEmployment)
                .params(scope.params())
                .query(LabourBookService::raw).list();

        boolean reveal = export && authz.can(PeoplePermissions.PERSON_VIEW_SENSITIVE,
                new AuthorizationTarget.LegalEntity(legalEntityId));
        Map<UUID, String> titles = new HashMap<>();
        List<UUID> titleIds = raws.stream().map(Raw::jobTitle).filter(Objects::nonNull).distinct().toList();
        if (!titleIds.isEmpty()) {
            jobs.jobTitles(titleIds).forEach((id, jt) -> titles.put(id, jt.nameVi()));
        }
        boolean salaryAccess = raws.stream().anyMatch(r -> access.can(PeoplePermissions.COMPENSATION_VIEW,
                r.employmentId(), asOf));
        if (salaryAccess) {
            masking.apply(PeoplePermissions.COMPENSATION_VIEW);
        }
        List<Entry> entries = new ArrayList<>();
        int salaries = 0;
        for (Raw r : raws) {
            String id = r.idLast4() == null ? null : "••••" + r.idLast4();
            if (reveal) {
                id = nationalId(r.personId());
                sensitive.recordSensitiveRead(PeoplePermissions.PERSON_VIEW_SENSITIVE, "hr.person", r.personId(),
                        legalEntityId);
            }
            Salary salary = null;
            if (salaryAccess && access.can(PeoplePermissions.COMPENSATION_VIEW, r.employmentId(), asOf)) {
                salary = salary(r.employmentId(), asOf);
                if (salary != null) {
                    salaries++;
                }
            }
            boolean ended = r.endDate() != null && r.endDate().isBefore(asOf);
            LocalDate termination = r.sepDate() != null ? r.sepDate() : ended ? r.endDate() : null;
            String status = ended ? "TERMINATED" : "TERMINATED".equals(r.status()) ? "ACTIVE" : r.status();
            String address = r.addressLine();
            entries.add(new Entry(r.employmentId(), r.employeeNo(), r.fullName(), r.gender(), r.birthDate(),
                    r.nationality(), r.idType(), id, address, r.province(), r.commune(), r.qualification(), r.skill(),
                    r.orgUnit(), r.position(), r.jobTitle(), r.jobTitle() == null ? null : titles.get(r.jobTitle()),
                    r.contractType(), r.contractNo(), r.contractStart(), r.contractEnd(), salary, r.si(), List.of(),
                    List.of(), r.hireDate(), status, termination, termination == null ? null : r.sepType(),
                    termination == null ? null : r.ground()));
        }
        if (salaries > 0) {
            audit.record(new BusinessAction("VIEW_SALARY", "hr", "legal_entity", legalEntityId, legalEntityId,
                    Map.of("purpose", "LABOUR_BOOK", "asOf", asOf.toString(), "records", String.valueOf(salaries))));
        }
        List<String> notes = new ArrayList<>();
        notes.add("training and discipline are placeholders until those Phase 2 features exist");
        if (!salaryAccess) {
            notes.add("salary columns are hidden: hr.compensation.view is required");
        }
        if (export && !reveal) {
            notes.add("national id numbers are masked: hr.person.view_sensitive is required to export them");
        }
        return new Book(legalEntityId, asOf, reveal, salaries, entries, notes);
    }

    private String nationalId(UUID personId) {
        UUID t = tenant();
        return db.jdbc().sql("""
                        select national_id_enc, subject_key_version from hr.person where tenant_id = :t and id = :p""")
                .param("t", t).param("p", personId)
                .query((rs, n) -> cipher.decrypt(Subject.person(t, personId), "national_id",
                        rs.getBytes(1), rs.getInt(2)))
                .optional().orElse(null);
    }

    private Salary salary(UUID employmentId, LocalDate asOf) {
        return db.jdbc().sql("""
                        select pay_basis, salary_kind, base_salary_amount, currency_code, valid_from
                          from hr.v_salary_record_masked
                         where tenant_id = :t and employment_id = :e and superseded_at is null and valid_from <= :d
                           and (valid_to is null or valid_to > :d)
                         order by valid_from desc limit 1""")
                .param("t", tenant()).param("e", employmentId).param("d", asOf)
                .query((rs, n) -> rs.getBigDecimal(3) == null ? null : new Salary(rs.getString(1), rs.getString(2),
                        rs.getBigDecimal(3), rs.getString(4), rs.getObject(5, LocalDate.class)))
                .optional().orElse(null);
    }

    private static Raw raw(ResultSet rs, int n) throws SQLException {
        return new Raw(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, LocalDate.class), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                rs.getObject(15, UUID.class), rs.getObject(16, UUID.class), rs.getObject(17, UUID.class),
                rs.getString(18), rs.getString(19), rs.getObject(20, LocalDate.class), rs.getObject(21, LocalDate.class),
                rs.getBoolean(22), rs.getObject(23, LocalDate.class), rs.getObject(24, LocalDate.class),
                rs.getString(25), rs.getObject(26, LocalDate.class), rs.getString(27), rs.getString(28));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
