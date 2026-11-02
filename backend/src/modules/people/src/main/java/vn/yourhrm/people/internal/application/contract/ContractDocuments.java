package vn.yourhrm.people.internal.application.contract;

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
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Contract files in Core's document service (BR-PPL-057, US-PPL-055/056; BR-CORE-100/101/102): the document types of
 * contracts and appendices, their owner resolver (a contract / appendix resolves to its employee, so the document
 * permissions follow the employee's data scope), and the bilingual generated-document definitions with the merge
 * fields {@link ContractDocumentService} supplies. Tenants upload their VN/EN templates in Core; output is PDF/A
 * with embedded Vietnamese fonts (Core's renderer, NFR-PPL-010).
 */
@Component
public class ContractDocuments implements DocumentTypeCatalogue, DocumentOwnerResolver, DocumentTemplateCatalogue {

    public static final String CONTRACT_OWNER = "hr.contract";
    public static final String APPENDIX_OWNER = "hr.contract_appendix";

    /** Document type of contract files (generated drafts and signed scans). */
    public static final String CONTRACT_DOC = "HR_LABOUR_CONTRACT";
    public static final String APPENDIX_DOC = "HR_CONTRACT_APPENDIX";

    /** Template codes (purpose LABOUR_CONTRACT). */
    public static final String CONTRACT_TEMPLATE = "HR_LABOUR_CONTRACT";
    public static final String APPENDIX_TEMPLATE = "HR_CONTRACT_APPENDIX";

    /** Merge fields of a contract (also available in appendix templates). */
    static final List<MergeField> CONTRACT_FIELDS = List.of(
            MergeField.text("employee.full_name", "Họ và tên người lao động", "Employee full name", "Nguyễn Thị Ánh"),
            MergeField.text("employee.no", "Mã nhân viên", "Employee number", "NV00012"),
            MergeField.text("employee.birth_date", "Ngày sinh", "Date of birth", "17/05/1990"),
            MergeField.text("employee.gender", "Giới tính", "Gender", "Nữ / Female"),
            MergeField.text("employee.nationality", "Quốc tịch", "Nationality", "VN"),
            MergeField.text("contract.no", "Số hợp đồng", "Contract number", "001/2026/HĐLĐ-HQ"),
            MergeField.text("contract.type_vi", "Loại hợp đồng (tiếng Việt)", "Contract type (Vietnamese)",
                    "Hợp đồng lao động xác định thời hạn"),
            MergeField.text("contract.type_en", "Loại hợp đồng (tiếng Anh)", "Contract type (English)",
                    "Definite-term labour contract"),
            MergeField.text("contract.start_date", "Ngày bắt đầu", "Start date", "01/02/2026"),
            MergeField.text("contract.end_date", "Ngày kết thúc", "End date", "31/01/2027"),
            MergeField.text("contract.probation_days", "Số ngày thử việc", "Probation days", "60"),
            MergeField.text("contract.sign_date", "Ngày ký", "Sign date", "25/01/2026"),
            MergeField.text("job.title_vi", "Chức danh (tiếng Việt)", "Job title (Vietnamese)", "Kỹ sư phần mềm"),
            MergeField.text("job.title_en", "Chức danh (tiếng Anh)", "Job title (English)", "Software engineer"),
            MergeField.text("signatory.name", "Người ký phía công ty", "Employer's signatory", "Nguyễn Văn A"),
            MergeField.text("signatory.title", "Chức vụ người ký", "Signatory's title", "Giám đốc / Director"));

    static final List<MergeField> APPENDIX_FIELDS = List.of(
            MergeField.text("appendix.no", "Số phụ lục", "Appendix number", "001/2026/HĐLĐ-HQ/PL01"),
            MergeField.text("appendix.effective_date", "Ngày hiệu lực", "Effective date", "01/07/2026"),
            MergeField.text("appendix.changes_vi", "Nội dung sửa đổi (tiếng Việt)", "Amended terms (Vietnamese)",
                    "Nơi làm việc: Chi nhánh Hà Nội"),
            MergeField.text("appendix.changes_en", "Nội dung sửa đổi (tiếng Anh)", "Amended terms (English)",
                    "Work location: Hanoi branch"));

    private final HrDb db;
    private final EmployeeAccess access;
    private final BusinessClock clock;

    public ContractDocuments(HrDb db, EmployeeAccess access, BusinessClock clock) {
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
                new DocumentTypeDefinition(CONTRACT_DOC, "hr", CONTRACT_OWNER, "Hợp đồng lao động", "Labour contract",
                        "CONFIDENTIAL", "EMPLOYEE_FILE", PeoplePermissions.CONTRACT_VIEW,
                        PeoplePermissions.CONTRACT_UPDATE, Set.of(), null),
                new DocumentTypeDefinition(APPENDIX_DOC, "hr", APPENDIX_OWNER, "Phụ lục hợp đồng lao động",
                        "Labour contract appendix", "CONFIDENTIAL", "EMPLOYEE_FILE", PeoplePermissions.CONTRACT_VIEW,
                        PeoplePermissions.CONTRACT_UPDATE, Set.of(), null));
    }

    @Override
    public List<DocumentTemplateDefinition> templates() {
        List<MergeField> appendix = new java.util.ArrayList<>(CONTRACT_FIELDS);
        appendix.addAll(APPENDIX_FIELDS);
        return List.of(
                new DocumentTemplateDefinition(CONTRACT_TEMPLATE, "hr", "LABOUR_CONTRACT", "Hợp đồng lao động",
                        "Labour contract", CONTRACT_DOC, CONTRACT_FIELDS),
                new DocumentTemplateDefinition(APPENDIX_TEMPLATE, "hr", "LABOUR_CONTRACT", "Phụ lục hợp đồng lao động",
                        "Labour contract appendix", APPENDIX_DOC, appendix));
    }

    @Override
    public Set<String> ownerEntityTypes() {
        return Set.of(CONTRACT_OWNER, APPENDIX_OWNER);
    }

    /** The employee the contract / appendix belongs to, as of today (BR-CORE-101). */
    @Override
    @HrTransactional(readOnly = true)
    public Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId) {
        String sql = CONTRACT_OWNER.equals(ownerEntityType)
                ? "select employment_id from hr.contract where tenant_id = :t and id = :id"
                : """
                  select c.employment_id from hr.contract_appendix a
                    join hr.contract c on c.tenant_id = a.tenant_id and c.id = a.contract_id
                   where a.tenant_id = :t and a.id = :id""";
        return db.jdbc().sql(sql).param("t", TenantContext.current().tenantId()).param("id", ownerEntityId)
                .query(UUID.class).optional()
                .flatMap(e -> access.target(e, clock.today()).map(AuthorizationTarget.class::cast));
    }
}
