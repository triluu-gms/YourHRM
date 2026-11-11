package vn.yourhrm.people.internal.application.onboarding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.HrMasking;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.MaskLevel;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Reconciliation and data-quality report of the opening-balance load (US-PPL-093, report R20; feeds BR-SAAS-072):
 * per legal entity the employees loaded, the active headcount on the go-live date, the total current base salary, the
 * registered dependants and the employees missing a tax code, SI number or salary bank account; plus the per-employee
 * discrepancy list (missing data, no assignment / contract / salary, concurrent employee without PIT primary
 * designation, unmapped address) and an Excel export for the customer's sign-off. The per-job control checks of each
 * import type (control sheet vs staged vs committed) are in Core's import reconciliation. Data scope of
 * {@code hr.import.run}; salary amounts only through {@code hr.v_salary_record_masked} (null when masked).
 */
@Service
public class OpeningBalanceReportService {

    public static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public record LegalEntitySummary(UUID legalEntityId, long employees, long activeHeadcount, long preHires,
            BigDecimal salaryTotal, long dependants, long missingTaxCode, long missingSiNumber, long missingBankAccount) {
    }

    public record Discrepancy(UUID employmentId, String employeeNo, String fullName, UUID legalEntityId, String status,
            List<String> issues) {
    }

    public record Report(LocalDate asOf, UUID legalEntityId, boolean salaryMasked, List<LegalEntitySummary> legalEntities,
            List<Discrepancy> discrepancies) {
    }

    public record Export(String fileName, String mediaType, byte[] content) {
    }

    private record Line(UUID employmentId, UUID personId, String employeeNo, String fullName, UUID le, String status,
            boolean hasTax, boolean hasSi, boolean hasBank, boolean hasAssignment, boolean hasContract,
            boolean hasSalary, BigDecimal salary, long dependants, boolean unmappedAddress, boolean noPrimary) {
    }

    private final HrDb db;
    private final EmployeeAccess access;
    private final HrMasking masking;
    private final AuditLog audit;
    private final BusinessClock clock;

    public OpeningBalanceReportService(HrDb db, EmployeeAccess access, HrMasking masking, AuditLog audit,
            BusinessClock clock) {
        this.db = db;
        this.access = access;
        this.masking = masking;
        this.audit = audit;
        this.clock = clock;
    }

    @HrTransactional(readOnly = true)
    public Report report(UUID legalEntityId, LocalDate asOf, boolean onlyOpeningLoad) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        MaskLevel level = masking.apply(PeoplePermissions.IMPORT_RUN);
        List<Line> lines = lines(legalEntityId, d, onlyOpeningLoad);
        Map<UUID, List<Line>> byLe = new LinkedHashMap<>();
        lines.forEach(l -> byLe.computeIfAbsent(l.le(), k -> new ArrayList<>()).add(l));
        boolean masked = level == MaskLevel.PII_AND_PAY;
        List<LegalEntitySummary> summaries = new ArrayList<>();
        byLe.forEach((le, ls) -> summaries.add(new LegalEntitySummary(le, ls.size(),
                ls.stream().filter(l -> "ACTIVE".equals(l.status()) || "SUSPENDED".equals(l.status())).count(),
                ls.stream().filter(l -> "PRE_HIRE".equals(l.status())).count(),
                masked ? null : ls.stream().map(Line::salary).filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                ls.stream().mapToLong(Line::dependants).sum(), ls.stream().filter(l -> !l.hasTax()).count(),
                ls.stream().filter(l -> !l.hasSi()).count(), ls.stream().filter(l -> !l.hasBank()).count())));
        List<Discrepancy> discrepancies = new ArrayList<>();
        for (Line l : lines) {
            List<String> issues = new ArrayList<>();
            add(issues, !l.hasTax(), "MISSING_TAX_CODE");
            add(issues, !l.hasSi(), "MISSING_SI_NUMBER");
            add(issues, !l.hasBank(), "MISSING_BANK_ACCOUNT");
            add(issues, !l.hasAssignment(), "NO_ASSIGNMENT");
            add(issues, !l.hasContract(), "NO_CONTRACT");
            add(issues, !l.hasSalary(), "NO_SALARY");
            add(issues, l.noPrimary(), "NO_PIT_PRIMARY");
            add(issues, l.unmappedAddress(), "UNMAPPED_ADDRESS");
            if (!issues.isEmpty()) {
                discrepancies.add(new Discrepancy(l.employmentId(), l.employeeNo(), l.fullName(), l.le(), l.status(),
                        issues));
            }
        }
        return new Report(d, legalEntityId, masked, summaries, discrepancies);
    }

    /** The report as an Excel workbook (summary + discrepancies) for the customer's sign-off; audited. */
    @HrTransactional(readOnly = true)
    public Export excel(UUID legalEntityId, LocalDate asOf, boolean onlyOpeningLoad) {
        Report r = report(legalEntityId, asOf, onlyOpeningLoad);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sum = wb.createSheet("DOI_SOAT");
            row(sum, 0, "Báo cáo đối soát số dư đầu kỳ nhân sự (R20) - ngày " + r.asOf());
            row(sum, 1, "Pháp nhân / Legal entity", "Số nhân viên / Employees", "Đang làm việc / Active",
                    "Chưa vào làm / Pre-hire", "Tổng lương cơ bản / Base salary total", "Người phụ thuộc / Dependants",
                    "Thiếu MST / No tax code", "Thiếu mã BHXH / No SI no.", "Thiếu tài khoản / No bank account");
            int i = 2;
            for (LegalEntitySummary s : r.legalEntities()) {
                row(sum, i++, s.legalEntityId(), s.employees(), s.activeHeadcount(), s.preHires(),
                        s.salaryTotal() == null ? "***" : s.salaryTotal(), s.dependants(), s.missingTaxCode(),
                        s.missingSiNumber(), s.missingBankAccount());
            }
            Sheet dis = wb.createSheet("CHENH_LECH");
            row(dis, 0, "Mã nhân viên / Employee no.", "Họ tên / Name", "Pháp nhân / Legal entity",
                    "Trạng thái / Status", "Vấn đề / Issues");
            i = 1;
            for (Discrepancy x : r.discrepancies()) {
                row(dis, i++, x.employeeNo(), x.fullName(), x.legalEntityId(), x.status(), String.join(", ", x.issues()));
            }
            wb.write(out);
            audit.record(new BusinessAction("EXPORT_OPENING_BALANCE_REPORT", "hr", "legal_entity", legalEntityId,
                    legalEntityId, Map.of("asOf", r.asOf().toString(), "employees",
                            String.valueOf(r.legalEntities().stream().mapToLong(LegalEntitySummary::employees).sum()))));
            return new Export("doi-soat-so-du-dau-ky-" + r.asOf() + ".xlsx", MEDIA_TYPE, out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void row(Sheet sheet, int index, Object... values) {
        Row row = sheet.createRow(index);
        for (int c = 0; c < values.length; c++) {
            Object v = values[c];
            if (v instanceof Number n) {
                row.createCell(c).setCellValue(n.doubleValue());
            } else {
                row.createCell(c).setCellValue(v == null ? "" : v.toString());
            }
        }
    }

    private static void add(List<String> issues, boolean condition, String code) {
        if (condition) {
            issues.add(code);
        }
    }

    private List<Line> lines(UUID legalEntityId, LocalDate asOf, boolean onlyOpeningLoad) {
        var scope = access.scope(PeoplePermissions.IMPORT_RUN, asOf, "s");
        return db.jdbc().sql("select e.id, e.person_id, e.employee_no, p.full_name, e.legal_entity_id, e.status, "
                        + "(p.tax_code_bidx is not null or p.national_id_type = 'CCCD') as has_tax, "
                        + "p.si_number_bidx is not null as has_si, "
                        + "exists (select 1 from hr.bank_account b where b.tenant_id = e.tenant_id and b.person_id = "
                        + "  e.person_id and b.status = 'ACTIVE' and b.valid_from <= :asOf "
                        + "  and (b.valid_to is null or b.valid_to > :asOf)) as has_bank, "
                        + "pa.org_unit_id is not null as has_assignment, "
                        + "exists (select 1 from hr.contract c where c.tenant_id = e.tenant_id and c.employment_id = e.id "
                        + "  and c.status in ('ACTIVE','SUSPENDED')) as has_contract, "
                        + "sal.found as has_salary, sal.base_salary_amount, "
                        + "(select count(*) from hr.pit_dependant d where d.tenant_id = e.tenant_id "
                        + "  and d.employment_id = e.id and d.status = 'APPROVED' "
                        + "  and d.month_range @> cast(date_trunc('month', cast(:asOf as date)) as date)) as dependants, "
                        + "exists (select 1 from hr.person_address a where a.tenant_id = e.tenant_id "
                        + "  and a.person_id = e.person_id and a.mapping_status = 'UNMAPPED') as unmapped, "
                        + "((select count(*) from hr.employment o where o.tenant_id = e.tenant_id "
                        + "   and o.person_id = e.person_id and o.status in ('PRE_HIRE','ACTIVE','SUSPENDED') "
                        + "   and o.hire_date <= :asOf) > 1 "
                        + " and not exists (select 1 from hr.primary_employment pe where pe.tenant_id = e.tenant_id "
                        + "   and pe.person_id = e.person_id and pe.status = 'ACTIVE' "
                        + "   and pe.valid_during @> cast(:asOf as date))) as no_primary "
                        + "from " + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN
                        + " join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id "
                        + " left join lateral (select true as found, v.base_salary_amount from hr.v_salary_record_masked v "
                        + "   where v.tenant_id = e.tenant_id and v.employment_id = e.id and v.superseded_at is null "
                        + "     and v.valid_from <= greatest(:asOf, e.hire_date) "
                        + "     and (v.valid_to is null or v.valid_to > greatest(:asOf, e.hire_date)) limit 1) sal on true "
                        + "where e.tenant_id = :t and e.status in ('PRE_HIRE','ACTIVE','SUSPENDED') "
                        + "  and (cast(:le as uuid) is null or e.legal_entity_id = :le) "
                        + "  and (not :onlyOb or e.source = 'OPENING_LOAD') and " + scope.sql()
                        + " order by e.legal_entity_id, e.employee_no")
                .params(scope.params())
                .param("t", TenantContext.current().tenantId()).param("asOf", asOf).param("le", legalEntityId)
                .param("onlyOb", onlyOpeningLoad)
                .query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getBoolean(7),
                        rs.getBoolean(8), rs.getBoolean(9), rs.getBoolean(10), rs.getBoolean(11),
                        rs.getObject(12) != null && rs.getBoolean(12), rs.getBigDecimal(13), rs.getLong(14),
                        rs.getBoolean(15), rs.getBoolean(16)))
                .list();
    }
}
