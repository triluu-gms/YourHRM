package vn.yourhrm.people.internal.application.report;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentOwnerResolver;
import vn.yourhrm.core.api.DocumentTemplateCatalogue;
import vn.yourhrm.core.api.DocumentTemplateDefinition;
import vn.yourhrm.core.api.DocumentTemplateDefinition.MergeField;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * What the statutory-report stream registers with Core (EP-PPL-09): the document types of generated reports, receipts
 * and labour-book entries with their owner resolver (a report resolves to its legal entity, an entry to its employee),
 * the bilingual PDF templates (purpose REPORT, Vietnamese fonts by Core's renderer), the deadline alert and its tenant
 * offsets (the alert templates are in {@link ReportNotificationTemplates}).
 */
@Component
public class ReportCatalogues implements DocumentTypeCatalogue, DocumentOwnerResolver, DocumentTemplateCatalogue,
        SettingCatalogue {

    /** Fields of {@link ReportKeys#TEMPLATE_LABOUR_BOOK_ENTRY} (Decree 145/2020 Art. 3 [verify-legal]). */
    public static final List<MergeField> ENTRY_FIELDS = List.of(
            MergeField.text("book.as_of", "Ngày lập", "As of", "01/11/2026"),
            MergeField.text("employee.no", "Mã nhân viên", "Employee number", "NV00012"),
            MergeField.text("employee.full_name", "Họ và tên", "Full name", "Nguyễn Thị Ánh"),
            MergeField.text("employee.gender", "Giới tính", "Gender", "Nữ"),
            MergeField.text("employee.birth_date", "Ngày sinh", "Date of birth", "17/05/1990"),
            MergeField.text("employee.nationality", "Quốc tịch", "Nationality", "VN"),
            MergeField.text("employee.national_id", "Số CCCD / hộ chiếu", "ID number", "001090000123"),
            MergeField.text("employee.residence", "Nơi cư trú", "Residence", "Số 1 Tràng Tiền, Hà Nội"),
            MergeField.text("qualification.level", "Trình độ chuyên môn kỹ thuật", "Qualification", "UNIVERSITY"),
            MergeField.text("qualification.skill", "Bậc trình độ kỹ năng nghề", "Vocational skill level", "3"),
            MergeField.text("job.title", "Vị trí việc làm / chức danh", "Job title", "Kỹ sư phần mềm"),
            MergeField.text("contract.type", "Loại hợp đồng", "Contract type", "INDEFINITE"),
            MergeField.text("contract.no", "Số hợp đồng", "Contract number", "001/2026/HĐLĐ-HQ"),
            MergeField.text("contract.start", "Ngày bắt đầu", "Start date", "01/01/2026"),
            MergeField.text("contract.end", "Ngày kết thúc", "End date", ""),
            MergeField.text("salary.basis", "Hình thức trả lương", "Pay basis", "GROSS"),
            MergeField.text("salary.amount", "Mức lương", "Salary", "20.000.000"),
            MergeField.text("si.participating", "Tham gia BHXH", "Social insurance", "Có"),
            MergeField.text("training", "Học nghề, đào tạo, bồi dưỡng", "Training", "—"),
            MergeField.text("discipline", "Kỷ luật lao động, trách nhiệm vật chất", "Discipline", "—"),
            MergeField.text("employment.hire_date", "Ngày bắt đầu làm việc", "Hire date", "01/01/2026"),
            MergeField.text("termination.date", "Ngày chấm dứt", "Termination date", ""),
            MergeField.text("termination.ground", "Lý do chấm dứt", "Termination ground", ""));

    /** Fields of {@link ReportKeys#TEMPLATE_LABOUR_CHANGE_6M}. */
    public static final List<MergeField> CHANGE_FIELDS = List.of(
            MergeField.text("report.form_code", "Mẫu số", "Form", "01/PLI"),
            MergeField.text("report.title", "Tên báo cáo", "Report title", "Báo cáo tình hình thay đổi về lao động"),
            MergeField.text("report.period", "Kỳ báo cáo", "Period", "01/01/2026 - 30/06/2026"),
            MergeField.text("report.headcount_start", "Số lao động đầu kỳ", "Headcount at start", "100"),
            MergeField.text("report.hires", "Số lao động tăng", "Hires", "10"),
            MergeField.text("report.terminations", "Số lao động giảm", "Terminations", "5"),
            MergeField.text("report.headcount_end", "Số lao động cuối kỳ", "Headcount at end", "105"),
            MergeField.text("report.female_end", "Trong đó lao động nữ", "Of which female", "50"),
            MergeField.text("report.indefinite_end", "HĐLĐ không xác định thời hạn", "Indefinite contracts", "80"),
            MergeField.text("report.definite_end", "HĐLĐ xác định thời hạn", "Definite contracts", "20"),
            MergeField.text("report.other_end", "Khác / chưa có hợp đồng", "Other / none", "5"),
            MergeField.text("report.notes", "Ghi chú", "Notes", ""));

    private final HrDb db;
    private final EmployeeAccess access;
    private final BusinessClock clock;

    public ReportCatalogues(HrDb db, EmployeeAccess access, BusinessClock clock) {
        this.db = db;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<DocumentTypeDefinition> documentTypes() {
        return List.of(
                new DocumentTypeDefinition(ReportKeys.REPORT_DOC, "hr", ReportKeys.OWNER_REPORT,
                        "Báo cáo lao động theo quy định", "Statutory labour report", "RESTRICTED", "STATUTORY_REPORT",
                        PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE, Set.of(),
                        null),
                new DocumentTypeDefinition(ReportKeys.RECEIPT_DOC, "hr", ReportKeys.OWNER_REPORT,
                        "Giấy biên nhận nộp báo cáo", "Report submission receipt", "CONFIDENTIAL", "STATUTORY_REPORT",
                        PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE, Set.of(),
                        null),
                new DocumentTypeDefinition(ReportKeys.LABOUR_BOOK_ENTRY_DOC, "hr", ReportKeys.OWNER_LABOUR_BOOK_ENTRY,
                        "Trích sổ quản lý lao động", "Labour book entry", "RESTRICTED", "EMPLOYEE_FILE",
                        PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE, Set.of(),
                        null));
    }

    @Override
    public List<DocumentTemplateDefinition> templates() {
        return List.of(
                new DocumentTemplateDefinition(ReportKeys.TEMPLATE_LABOUR_BOOK_ENTRY, "hr", "REPORT",
                        "Sổ quản lý lao động (trích một người lao động)", "Labour book entry",
                        ReportKeys.LABOUR_BOOK_ENTRY_DOC, ENTRY_FIELDS),
                new DocumentTemplateDefinition(ReportKeys.TEMPLATE_LABOUR_CHANGE_6M, "hr", "REPORT",
                        "Báo cáo tình hình thay đổi về lao động 6 tháng", "6-monthly labour change report",
                        ReportKeys.REPORT_DOC, CHANGE_FIELDS));
    }

    @Override
    public Set<String> ownerEntityTypes() {
        return Set.of(ReportKeys.OWNER_REPORT, ReportKeys.OWNER_LABOUR_BOOK_ENTRY);
    }

    @Override
    @HrTransactional(readOnly = true)
    public Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId) {
        if (ReportKeys.OWNER_LABOUR_BOOK_ENTRY.equals(ownerEntityType)) {
            return access.target(ownerEntityId, clock.today()).map(AuthorizationTarget.class::cast);
        }
        return db.jdbc().sql("select legal_entity_id from hr.statutory_report where tenant_id = :t and id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", ownerEntityId).query(UUID.class)
                .optional().map(AuthorizationTarget.LegalEntity::new);
    }

    @Override
    public List<SettingDefinition> settings() {
        return List.of(new SettingDefinition(ReportKeys.SETTING_ALERT_DAYS, "JSON", List.of(), List.of(15, 5, 1),
                Set.of("TENANT", "LEGAL_ENTITY"), false, false,
                "Các mốc cảnh báo hạn nộp báo cáo lao động (số ngày trước hạn)",
                "Statutory labour report deadline alert offsets (days before the due date)"));
    }
}
