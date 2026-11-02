package vn.yourhrm.people.internal.application.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentOwnerResolver;
import vn.yourhrm.core.api.DocumentTemplateCatalogue;
import vn.yourhrm.core.api.DocumentTemplateDefinition;
import vn.yourhrm.core.api.DocumentTemplateDefinition.MergeField;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Decision files in Core's document service (BR-PPL-130, US-PPL-057/058): the document type {@link #DECISION_DOC}
 * (generated decisions and signed scans), its owner resolver (a decision resolves to its employee, a collective one
 * to its legal entity), and one bilingual template definition per P1 decision type, {@code HR_DECISION_<TYPE>}
 * (purpose DECISION), with the common fields plus the type's {@link DecisionMergeFields}.
 */
@Component
public class DecisionDocuments implements DocumentTypeCatalogue, DocumentOwnerResolver, DocumentTemplateCatalogue {

    public static final String OWNER = "hr.decision";
    public static final String DECISION_DOC = "HR_DECISION";
    public static final String TEMPLATE_PREFIX = "HR_DECISION_";

    /** Vietnamese / English names of the P1 decision types. */
    public static final Map<String, String[]> TYPE_NAMES = Map.ofEntries(
            Map.entry("HIRE", new String[] {"Quyết định tuyển dụng / tiếp nhận", "Decision on recruitment"}),
            Map.entry("PROBATION_RESULT", new String[] {"Quyết định về kết quả thử việc", "Decision on probation result"}),
            Map.entry("TRANSFER", new String[] {"Quyết định điều chuyển", "Decision on transfer"}),
            Map.entry("PROMOTION", new String[] {"Quyết định bổ nhiệm / thăng chức", "Decision on promotion"}),
            Map.entry("DEMOTION", new String[] {"Quyết định miễn nhiệm / giáng chức", "Decision on demotion"}),
            Map.entry("TEMPORARY_TRANSFER", new String[] {"Quyết định tạm thời chuyển làm công việc khác",
                    "Decision on temporary transfer"}),
            Map.entry("JOB_CHANGE", new String[] {"Quyết định thay đổi công việc", "Decision on job change"}),
            Map.entry("SALARY_CHANGE", new String[] {"Quyết định điều chỉnh lương", "Decision on salary change"}),
            Map.entry("CONTRACT_SUSPENSION", new String[] {"Quyết định tạm hoãn thực hiện hợp đồng lao động",
                    "Decision on suspension of the labour contract"}),
            Map.entry("TERMINATION", new String[] {"Quyết định chấm dứt hợp đồng lao động",
                    "Decision on termination of the labour contract"}),
            Map.entry("RETIREMENT", new String[] {"Quyết định nghỉ hưu", "Decision on retirement"}));

    /** Fields every decision template may use. */
    public static final List<MergeField> COMMON_FIELDS = List.of(
            MergeField.text("decision.no", "Số quyết định", "Decision number", "001/2026/QĐ-HQ"),
            MergeField.text("decision.type_vi", "Loại quyết định (tiếng Việt)", "Decision type (Vietnamese)",
                    "Quyết định điều chuyển"),
            MergeField.text("decision.type_en", "Loại quyết định (tiếng Anh)", "Decision type (English)",
                    "Decision on transfer"),
            MergeField.text("decision.effective_date", "Ngày hiệu lực", "Effective date", "01/03/2026"),
            MergeField.text("decision.sign_date", "Ngày ký", "Sign date", "25/02/2026"),
            MergeField.text("employee.full_name", "Họ và tên", "Full name", "Nguyễn Thị Ánh"),
            MergeField.text("employee.no", "Mã nhân viên", "Employee number", "NV00012"),
            MergeField.text("employee.birth_date", "Ngày sinh", "Date of birth", "17/05/1990"),
            MergeField.text("job.title_vi", "Chức danh (tiếng Việt)", "Job title (Vietnamese)", "Kỹ sư phần mềm"),
            MergeField.text("job.title_en", "Chức danh (tiếng Anh)", "Job title (English)", "Software engineer"),
            MergeField.text("signatory.name", "Người ký", "Signatory", "Nguyễn Văn A"),
            MergeField.text("signatory.title", "Chức vụ người ký", "Signatory's title", "Giám đốc / Director"));

    private final HrDb db;
    private final EmployeeAccess access;
    private final BusinessClock clock;
    private final List<DecisionMergeFields> typeFields;

    public DecisionDocuments(HrDb db, EmployeeAccess access, BusinessClock clock, List<DecisionMergeFields> typeFields) {
        this.db = db;
        this.access = access;
        this.clock = clock;
        this.typeFields = typeFields;
    }

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<DocumentTypeDefinition> documentTypes() {
        return List.of(new DocumentTypeDefinition(DECISION_DOC, "hr", OWNER, "Quyết định nhân sự", "HR decision",
                "CONFIDENTIAL", "EMPLOYEE_FILE", PeoplePermissions.DECISION_VIEW, PeoplePermissions.DECISION_UPDATE,
                Set.of(), null));
    }

    @Override
    public List<DocumentTemplateDefinition> templates() {
        List<DocumentTemplateDefinition> out = new ArrayList<>();
        for (String type : new TreeSet<>(DecisionService.TYPES)) {
            String[] name = TYPE_NAMES.getOrDefault(type, new String[] {type, type});
            List<MergeField> fields = new ArrayList<>(COMMON_FIELDS);
            providers(type).forEach(p -> fields.addAll(p.fields()));
            out.add(new DocumentTemplateDefinition(TEMPLATE_PREFIX + type, "hr", "DECISION", name[0], name[1],
                    DECISION_DOC, fields));
        }
        return out;
    }

    List<DecisionMergeFields> providers(String type) {
        return typeFields.stream().filter(p -> p.decisionType().equals(type)).toList();
    }

    @Override
    public Set<String> ownerEntityTypes() {
        return Set.of(OWNER);
    }

    @Override
    @HrTransactional(readOnly = true)
    public Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId) {
        record Row(UUID employmentId, UUID legalEntityId) {
        }
        return db.jdbc().sql("select employment_id, legal_entity_id from hr.decision where tenant_id = :t and id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", ownerEntityId)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional()
                .flatMap(r -> r.employmentId() == null
                        ? Optional.<AuthorizationTarget>of(new AuthorizationTarget.LegalEntity(r.legalEntityId()))
                        : access.target(r.employmentId(), clock.today()).map(AuthorizationTarget.class::cast));
    }
}
