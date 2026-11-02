package vn.yourhrm.people.internal.application.family;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentTemplateCatalogue;
import vn.yourhrm.core.api.DocumentTemplateDefinition;
import vn.yourhrm.core.api.DocumentTemplateDefinition.MergeField;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * What the family / dependant stream registers with Core's engines: the approval process type of dependant
 * registrations and end requests (US-PPL-029/074), the evidence document types (HR and ESS uploads) and the dependant
 * registration list template (US-PPL-030).
 */
@Component
public class FamilyCatalogues implements ProcessTypeCatalogue, DocumentTypeCatalogue, DocumentTemplateCatalogue {

    /** Approval of a dependant registration or end request (BR-PPL-017). */
    public static final String PROCESS_DEPENDANT_CHANGE = "hr.DEPENDANT_CHANGE";
    /** Workflow subject type: the {@code hr.pit_dependant} row (a PENDING registration or a DRAFT end request). */
    public static final String SUBJECT_TYPE = "hr.pit_dependant";

    /** Evidence (birth certificate, school certificate, disability certificate ...) uploaded by HR. */
    public static final String DOC_EVIDENCE = "HR_DEPENDANT_EVIDENCE";
    /** The same evidence uploaded by the employee in ESS (managed with the ESS request permission). */
    public static final String DOC_EVIDENCE_ESS = "HR_DEPENDANT_EVIDENCE_ESS";
    /** Evidence documents belong to the family member. */
    public static final String OWNER_FAMILY_MEMBER = "hr.family_member";
    /** {@code hr.hr_document.category_code} of evidence links. */
    public static final String EVIDENCE_CATEGORY = "PIT_DEPENDANT_EVIDENCE";

    /** The generated dependant registration list (PDF) and its template (BR-PPL-017, US-PPL-030). */
    public static final String DOC_REGISTRATION_LIST = "HR_DEPENDANT_REGISTRATION_LIST";
    public static final String OWNER_REGISTRATION_LIST = "hr.dependant_registration_list";
    public static final String TEMPLATE_REGISTRATION_LIST = "HR_DEPENDANT_REGISTRATION";

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<ProcessTypeDefinition> processTypes() {
        return List.of(new ProcessTypeDefinition(PROCESS_DEPENDANT_CHANGE, "Đăng ký / thay đổi người phụ thuộc",
                "PIT dependant registration / change",
                Map.of("type", "object", "properties", Map.of(
                        "action", Map.of("type", "string", "enum", List.of("REGISTER", "END")),
                        "relationshipCode", Map.of("type", "string"),
                        "reasonCode", Map.of("type", "string"),
                        "fromMonth", Map.of("type", "string"),
                        "toMonth", Map.of("type", "string"))),
                List.of("action", "reasonCode"), Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO), false,
                false,
                List.of(new StepTemplate("hr", "Nhân sự phê duyệt", "HR approval",
                        ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", "HR_ADMIN"), true))));
    }

    @Override
    public List<DocumentTypeDefinition> documentTypes() {
        return List.of(
                new DocumentTypeDefinition(DOC_EVIDENCE, "hr", OWNER_FAMILY_MEMBER, "Hồ sơ chứng minh người phụ thuộc",
                        "Dependant evidence", "CONFIDENTIAL", "EMPLOYEE_FILE", PeoplePermissions.DEPENDANT_VIEW,
                        PeoplePermissions.DEPENDANT_UPDATE, Set.of("application/pdf", "image/jpeg", "image/png"), null),
                new DocumentTypeDefinition(DOC_EVIDENCE_ESS, "hr", OWNER_FAMILY_MEMBER,
                        "Hồ sơ chứng minh người phụ thuộc (nhân viên gửi)", "Dependant evidence (employee upload)",
                        "CONFIDENTIAL", "EMPLOYEE_FILE", PeoplePermissions.ESS_VIEW, PeoplePermissions.ESS_REQUEST,
                        Set.of("application/pdf", "image/jpeg", "image/png"), null),
                new DocumentTypeDefinition(DOC_REGISTRATION_LIST, "hr", OWNER_REGISTRATION_LIST,
                        "Tờ khai / danh sách đăng ký người phụ thuộc", "Dependant registration list", "RESTRICTED",
                        "TAX_DECLARATION", PeoplePermissions.STATUTORY_REPORT_VIEW,
                        PeoplePermissions.STATUTORY_REPORT_EXECUTE, Set.of(), null));
    }

    @Override
    public List<DocumentTemplateDefinition> templates() {
        return List.of(new DocumentTemplateDefinition(TEMPLATE_REGISTRATION_LIST, "hr", "REPORT",
                "Đăng ký người phụ thuộc giảm trừ gia cảnh", "PIT dependant registration", DOC_REGISTRATION_LIST,
                List.of(MergeField.text("report.form_code", "Mẫu số", "Form", "07/ĐK-NPT-TNCN"),
                        MergeField.text("report.period", "Kỳ", "Period", "01/2026 - 03/2026"),
                        MergeField.text("report.registered_count", "Số NPT đăng ký mới", "New dependants", "2"),
                        MergeField.text("report.ended_count", "Số NPT kết thúc", "Ended dependants", "1"),
                        MergeField.text("report.rows", "Danh sách (mỗi dòng một người)", "Rows (one per line)",
                                "1 | Nguyễn Văn An | ••••1234 | Nguyễn Thị Bình | 01/02/2015 | Con | 01/2026 | 02/2033"))));
    }
}
