package vn.yourhrm.people.internal.application.family;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.DocumentGeneration;
import vn.yourhrm.core.api.DocumentGeneration.GenerateRequest;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Dependant registration declaration / list per legal entity and period (BR-PPL-017, US-PPL-030; form
 * 07/ĐK-NPT-TNCN or its successor, {@code [verify-legal]}, high uncertainty: the form code comes from the legal
 * parameter {@link FamilyLegalKeys#REGISTRATION_FORM_CODE} and the layout is a generic list until confirmed).
 *
 * <p>Lists the claims of the legal entity's employments (claiming employment) that START in the period (REGISTER) or
 * END in it (END; incl. the part of a claim transferred to another employer), restricted to the caller's data scope.
 * Identifiers (taxpayer tax code — the personal id number when no tax code is recorded, C18 — and the dependant's tax
 * code or id number) are printed in clear only for callers holding {@code hr.person.view_sensitive} on the employee;
 * exports are audited. Excel is built here (Apache POI); the PDF comes from the tenant's Core document template
 * {@link FamilyCatalogues#TEMPLATE_REGISTRATION_LIST} (PDF/A, Vietnamese fonts by Core's renderer). Core templates have
 * no repeating sections, so the PDF receives the list as one multi-line field {@code report.rows} (one line per
 * dependant; the template prints it with {@code white-space: pre-line}).
 */
@Service
public class DependantRegistrationReport {

    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter VN_MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    public record Line(int no, String changeType, UUID employmentId, String employeeNo, String taxpayerName,
            String taxpayerTaxCode, UUID dependantId, String dependantName, LocalDate birthDate,
            String dependantIdNo, String relationshipCode, String reasonCode, LocalDate fromMonth, LocalDate toMonth,
            String status) {
    }

    public record Report(UUID legalEntityId, LocalDate fromMonth, LocalDate toMonth, String formCode,
            int registeredCount, int endedCount, boolean unmasked, List<Line> lines) {
    }

    public record PdfResult(UUID documentId, String documentNumber, String mediaType, Documents.DownloadLink download) {
    }

    private final HrDb db;
    private final EmployeeAccess access;
    private final PiiCipher cipher;
    private final PeopleLegalParameters legal;
    private final DocumentGeneration generation;
    private final Documents documents;
    private final AuditLog audit;
    private final BusinessClock clock;

    public DependantRegistrationReport(HrDb db, EmployeeAccess access, PiiCipher cipher, PeopleLegalParameters legal,
            DocumentGeneration generation, Documents documents, AuditLog audit, BusinessClock clock) {
        this.db = db;
        this.access = access;
        this.cipher = cipher;
        this.legal = legal;
        this.generation = generation;
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
    }

    /** On-screen list: identifiers always masked. */
    @HrTransactional(readOnly = true)
    public Report view(UUID legalEntityId, LocalDate fromMonth, LocalDate toMonth) {
        return build(PeoplePermissions.STATUTORY_REPORT_VIEW, legalEntityId, fromMonth, toMonth, false);
    }

    /** Excel export (.xlsx); audited. */
    @HrTransactional(readOnly = true)
    public byte[] excel(UUID legalEntityId, LocalDate fromMonth, LocalDate toMonth) {
        Report r = build(PeoplePermissions.STATUTORY_REPORT_EXECUTE, legalEntityId, fromMonth, toMonth, true);
        byte[] out = workbook(r);
        auditExport(r, "XLSX", null);
        return out;
    }

    /** PDF through the tenant's Core template; stored as a RESTRICTED document of the legal entity; audited. */
    @HrTransactional(readOnly = true)
    public PdfResult pdf(UUID legalEntityId, LocalDate fromMonth, LocalDate toMonth) {
        Report r = build(PeoplePermissions.STATUTORY_REPORT_EXECUTE, legalEntityId, fromMonth, toMonth, true);
        StringBuilder rows = new StringBuilder();
        for (Line l : r.lines()) {
            rows.append(l.no()).append(" | ").append("REGISTER".equals(l.changeType()) ? "Đăng ký" : "Kết thúc")
                    .append(" | ").append(l.taxpayerName()).append(" | ").append(nz(l.taxpayerTaxCode()))
                    .append(" | ").append(l.dependantName()).append(" | ")
                    .append(l.birthDate() == null ? "" : VN_DATE.format(l.birthDate())).append(" | ")
                    .append(nz(l.dependantIdNo())).append(" | ").append(l.relationshipCode()).append(" | ")
                    .append(VN_MONTH.format(l.fromMonth())).append(" | ")
                    .append(l.toMonth() == null ? "" : VN_MONTH.format(l.toMonth())).append('\n');
        }
        Map<String, Object> data = new HashMap<>();
        data.put("report.form_code", r.formCode());
        data.put("report.period", VN_MONTH.format(r.fromMonth()) + " - " + VN_MONTH.format(r.toMonth()));
        data.put("report.registered_count", String.valueOf(r.registeredCount()));
        data.put("report.ended_count", String.valueOf(r.endedCount()));
        data.put("report.rows", rows.length() == 0 ? "-" : rows.toString().stripTrailing());
        Generated g = generation.generate(new GenerateRequest(FamilyCatalogues.TEMPLATE_REGISTRATION_LIST,
                legalEntityId, Ids.newId(), null, "Đăng ký người phụ thuộc " + data.get("report.period"),
                clock.today(), data));
        auditExport(r, "PDF", g.documentId());
        return new PdfResult(g.documentId(), g.documentNumber(), g.mediaType(), documents.downloadLink(g.documentId(),
                null));
    }

    private Report build(String permission, UUID legalEntityId, LocalDate fromMonth, LocalDate toMonth,
            boolean unmaskIfAllowed) {
        if (legalEntityId == null || fromMonth == null || toMonth == null || toMonth.isBefore(fromMonth)) {
            throw ApiException.unprocessable("invalid-period", "legalEntityId, fromMonth and toMonth (≥ fromMonth) are required");
        }
        LocalDate from = fromMonth.withDayOfMonth(1);
        LocalDate to = toMonth.withDayOfMonth(1);
        LocalDate today = clock.today();
        String formCode = legal.require(FamilyLegalKeys.REGISTRATION_FORM_CODE, new Scope(legalEntityId, null), to)
                .asString();
        UUID tenant = TenantContext.current().tenantId();
        var scope = access.scope(permission, today, "s");
        record Raw(String changeType, UUID employmentId, String employeeNo, UUID personId, String taxpayerName,
                byte[] taxEnc, byte[] nidEnc, String taxLast4, String nidLast4, int personKey, UUID dependantId,
                UUID memberId, String memberName, LocalDate birth, byte[] mTaxEnc, byte[] mNidEnc, String mTaxLast4,
                String mNidLast4, int memberKey, String relationship, String reason, LocalDate fromMonth,
                LocalDate toMonth, String status) {
        }
        List<Raw> raws = db.jdbc().sql("""
                        select x.change_type, e.id, e.employee_no, p.id, p.full_name, p.tax_code_enc, p.national_id_enc,
                               p.tax_code_last4, p.national_id_last4, p.subject_key_version, d.id, f.id, f.full_name,
                               f.birth_date, f.tax_code_enc, f.national_id_enc, f.tax_code_last4, f.national_id_last4,
                               f.subject_key_version, f.relationship_code, d.dependant_reason_code, d.from_month,
                               d.to_month, d.status
                          from hr.pit_dependant d
                          join hr.family_member f on f.tenant_id = d.tenant_id and f.id = d.family_member_id
                          join hr.person p on p.tenant_id = f.tenant_id and p.id = f.person_id
                          join (""" + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN + """
                               ) on e.tenant_id = d.tenant_id and e.id = d.employment_id
                          cross join lateral (
                                select 'REGISTER' as change_type where d.from_month between :from and :to
                                union all
                                select 'END' where d.to_month between :from and :to) x
                         where d.tenant_id = :t and e.legal_entity_id = :le and d.status in ('APPROVED','ENDED')
                           and\s""" + scope.sql() + """
                         order by x.change_type desc, p.full_name, f.full_name, d.from_month""")
                .param("t", tenant).param("le", legalEntityId).param("from", from).param("to", to)
                .param("asOf", today).params(scope.params())
                .query((rs, n) -> new Raw(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getBytes(6), rs.getBytes(7), rs.getString(8),
                        rs.getString(9), rs.getInt(10), rs.getObject(11, UUID.class), rs.getObject(12, UUID.class),
                        rs.getString(13), rs.getObject(14, LocalDate.class), rs.getBytes(15), rs.getBytes(16),
                        rs.getString(17), rs.getString(18), rs.getInt(19), rs.getString(20), rs.getString(21),
                        rs.getObject(22, LocalDate.class), rs.getObject(23, LocalDate.class), rs.getString(24)))
                .list();
        boolean anyUnmasked = false;
        List<Line> lines = new java.util.ArrayList<>();
        int no = 0;
        for (Raw r : raws) {
            boolean clear = unmaskIfAllowed && access.can(PeoplePermissions.PERSON_VIEW_SENSITIVE, r.employmentId(), today);
            anyUnmasked |= clear;
            String taxpayerCode;
            String dependantCode;
            if (clear) {
                Subject ps = Subject.person(tenant, r.personId());
                taxpayerCode = r.taxEnc() != null ? cipher.decrypt(ps, "tax_code", r.taxEnc(), r.personKey())
                        : cipher.decrypt(ps, "national_id", r.nidEnc(), r.personKey());
                Subject ms = Subject.familyMember(tenant, r.memberId());
                dependantCode = r.mTaxEnc() != null ? cipher.decrypt(ms, "tax_code", r.mTaxEnc(), r.memberKey())
                        : cipher.decrypt(ms, "national_id", r.mNidEnc(), r.memberKey());
            } else {
                taxpayerCode = masked(r.taxLast4() != null ? r.taxLast4() : r.nidLast4());
                dependantCode = masked(r.mTaxLast4() != null ? r.mTaxLast4() : r.mNidLast4());
            }
            lines.add(new Line(++no, r.changeType(), r.employmentId(), r.employeeNo(), r.taxpayerName(), taxpayerCode,
                    r.dependantId(), r.memberName(), r.birth(), dependantCode, r.relationship(), r.reason(),
                    r.fromMonth(), r.toMonth(), r.status()));
        }
        int registered = (int) lines.stream().filter(l -> "REGISTER".equals(l.changeType())).count();
        return new Report(legalEntityId, from, to, formCode, registered, lines.size() - registered, anyUnmasked, lines);
    }

    private static String masked(String last4) {
        return last4 == null ? null : "••••" + last4;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void auditExport(Report r, String format, UUID documentId) {
        audit.record(new BusinessAction("EXPORT", "hr", "dependant_registration_list",
                documentId != null ? documentId : r.legalEntityId(), r.legalEntityId(),
                Map.of("format", format, "period", YearMonth.from(r.fromMonth()) + ".." + YearMonth.from(r.toMonth()),
                        "rows", String.valueOf(r.lines().size()), "unmasked", String.valueOf(r.unmasked()))));
    }

    private static byte[] workbook(Report r) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("NPT");
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setFontName("Times New Roman");
            Font normal = wb.createFont();
            normal.setFontName("Times New Roman");
            CellStyle title = wb.createCellStyle();
            title.setFont(bold);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            header.setWrapText(true);
            for (CellStyle s : List.of(header)) {
                s.setBorderBottom(BorderStyle.THIN);
                s.setBorderTop(BorderStyle.THIN);
                s.setBorderLeft(BorderStyle.THIN);
                s.setBorderRight(BorderStyle.THIN);
            }
            CellStyle cell = wb.createCellStyle();
            cell.setFont(normal);
            cell.setBorderBottom(BorderStyle.THIN);
            cell.setBorderTop(BorderStyle.THIN);
            cell.setBorderLeft(BorderStyle.THIN);
            cell.setBorderRight(BorderStyle.THIN);

            String[] cols = {"STT", "Loại thay đổi", "Mã NV", "Họ và tên người nộp thuế", "MST người nộp thuế",
                    "Họ và tên người phụ thuộc", "Ngày sinh", "MST / Số định danh NPT", "Quan hệ với người nộp thuế",
                    "Lý do giảm trừ", "Từ tháng", "Đến tháng"};
            Row t1 = sheet.createRow(0);
            t1.createCell(0).setCellValue("DANH SÁCH ĐĂNG KÝ NGƯỜI PHỤ THUỘC GIẢM TRỪ GIA CẢNH (Mẫu " + r.formCode() + ")");
            t1.getCell(0).setCellStyle(title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, cols.length - 1));
            Row t2 = sheet.createRow(1);
            t2.createCell(0).setCellValue("Kỳ: " + VN_MONTH.format(r.fromMonth()) + " - " + VN_MONTH.format(r.toMonth())
                    + "   Đăng ký mới: " + r.registeredCount() + "   Kết thúc: " + r.endedCount());
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, cols.length - 1));
            Row h = sheet.createRow(3);
            for (int i = 0; i < cols.length; i++) {
                h.createCell(i).setCellValue(cols[i]);
                h.getCell(i).setCellStyle(header);
            }
            int rowNo = 4;
            for (Line l : r.lines()) {
                Row row = sheet.createRow(rowNo++);
                String[] v = {String.valueOf(l.no()), "REGISTER".equals(l.changeType()) ? "Đăng ký" : "Kết thúc",
                        nz(l.employeeNo()), l.taxpayerName(), nz(l.taxpayerTaxCode()), l.dependantName(),
                        l.birthDate() == null ? "" : VN_DATE.format(l.birthDate()), nz(l.dependantIdNo()),
                        l.relationshipCode(), nz(l.reasonCode()), VN_MONTH.format(l.fromMonth()),
                        l.toMonth() == null ? "" : VN_MONTH.format(l.toMonth())};
                for (int i = 0; i < v.length; i++) {
                    row.createCell(i).setCellValue(v[i]);
                    row.getCell(i).setCellStyle(cell);
                }
            }
            for (int i = 0; i < cols.length; i++) {
                sheet.setColumnWidth(i, i == 0 ? 6 * 256 : 22 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Writing the dependant registration list failed", e);
        }
    }
}
