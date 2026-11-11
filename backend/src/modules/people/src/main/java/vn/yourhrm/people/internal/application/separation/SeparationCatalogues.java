package vn.yourhrm.people.internal.application.separation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentOwnerResolver;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * What the separation stream registers with Core (EP-PPL-08): the approval process types (HR-initiated separation,
 * ESS resignation, withdrawal of an approved resignation), the tenant settings (sub-reasons, settlement alert offsets),
 * the alert templates and the evidence document type of protected-employee exceptions with its owner resolver (a
 * separation resolves to its employee).
 *
 * <p>The HR approval step of {@code hr.SEPARATION} is the "HR manager approval" of US-PPL-063: the requester is never
 * their own approver (Core excludes them), so the exception of a protected employee is always approved by a second HR
 * user; tenants can add steps with the condition field {@code protectedOverride}.
 */
@Component
public class SeparationCatalogues implements ProcessTypeCatalogue, NotificationTemplateCatalogue, SettingCatalogue,
        DocumentTypeCatalogue, DocumentOwnerResolver {

    private static final StepTemplate HR_STEP = new StepTemplate("hr", "Nhân sự phê duyệt", "HR approval",
            ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", "HR_ADMIN"), true);

    private final HrDb db;
    private final EmployeeAccess access;
    private final BusinessClock clock;

    public SeparationCatalogues(HrDb db, EmployeeAccess access, BusinessClock clock) {
        this.db = db;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<ProcessTypeDefinition> processTypes() {
        Map<String, Object> summary = Map.of("type", "object", "properties", Map.of(
                "employeeNo", Map.of("type", "string"),
                "legalGroundCode", Map.of("type", "string"),
                "lastWorkingDate", Map.of("type", "string"),
                "noticeShortfallDays", Map.of("type", "integer"),
                "protectedEmployee", Map.of("type", "boolean")));
        return List.of(
                new ProcessTypeDefinition(SeparationKeys.PROCESS_SEPARATION, "Chấm dứt hợp đồng lao động",
                        "Termination of employment", summary,
                        List.of("separationType", "initiatedBy", "protectedOverride"),
                        Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO), false, false, List.of(HR_STEP)),
                new ProcessTypeDefinition(SeparationKeys.PROCESS_RESIGNATION, "Đơn xin nghỉ việc", "Resignation",
                        summary, List.of("noticeShortfall"), Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO),
                        false, false,
                        List.of(new StepTemplate("manager", "Quản lý trực tiếp", "Line manager",
                                ApproverResolver.of(ApproverResolver.Type.LINE_MANAGER), true), HR_STEP)),
                new ProcessTypeDefinition(SeparationKeys.PROCESS_RESIGNATION_WITHDRAWAL,
                        "Rút đơn nghỉ việc đã được duyệt", "Withdrawal of an approved resignation", summary,
                        List.of(), Set.of(WorkflowAction.REQUEST_INFO), false, false, List.of(HR_STEP)));
    }

    @Override
    public List<SettingDefinition> settings() {
        return List.of(
                new SettingDefinition(SeparationKeys.SETTING_SUB_REASONS, "JSON", List.of(), List.of(),
                        Set.of("TENANT"), false, false,
                        "Lý do nghỉ việc chi tiết của doanh nghiệp theo từng căn cứ pháp lý "
                                + "([{\"code\",\"groundCode\",\"nameVi\",\"nameEn\",\"active\"}])",
                        "Tenant separation sub-reasons under a legal ground "
                                + "([{\"code\",\"groundCode\",\"nameVi\",\"nameEn\",\"active\"}])"),
                new SettingDefinition(SeparationKeys.SETTING_SETTLEMENT_ALERT_DAYS, "JSON", List.of(), List.of(3, 1),
                        Set.of("TENANT", "LEGAL_ENTITY"), false, false,
                        "Các mốc cảnh báo hạn quyết toán khi nghỉ việc (số ngày trước hạn)",
                        "Final-settlement deadline alert offsets (days before the due date)"));
    }

    @Override
    public List<NotificationTemplateDefinition> templates() {
        List<NotificationTemplateDefinition> out = new ArrayList<>();
        Placeholder employeeNo = new Placeholder("employeeNo", Kind.TEXT);
        add(out, SeparationKeys.EVENT_SETTLEMENT_DUE, List.of(employeeNo, new Placeholder("terminationDate", Kind.DATE),
                        new Placeholder("dueDate", Kind.DATE), new Placeholder("daysLeft", Kind.NUMBER)),
                "Hạn quyết toán nghỉ việc của nhân viên {{employeeNo}}",
                "Nhân viên {{employeeNo}} đã nghỉ việc ngày {{terminationDate}}; hạn thanh toán đầy đủ các khoản là "
                        + "{{dueDate}} (còn {{daysLeft}} ngày). Vui lòng kiểm tra quyết toán với bộ phận lương.",
                "Final settlement due for employee {{employeeNo}}",
                "Employee {{employeeNo}} left on {{terminationDate}}; all amounts must be settled by {{dueDate}} "
                        + "({{daysLeft}} days left). Please check the final settlement with payroll.");
        add(out, SeparationKeys.EVENT_DRAFT_OPENED, List.of(employeeNo, new Placeholder("groundVi", Kind.TEXT),
                        new Placeholder("groundEn", Kind.TEXT), new Placeholder("lastWorkingDate", Kind.DATE)),
                "Hồ sơ nghỉ việc nháp cho nhân viên {{employeeNo}}",
                "Một hồ sơ chấm dứt hợp đồng nháp ({{groundVi}}, ngày làm việc cuối {{lastWorkingDate}}) đã được mở cho "
                        + "nhân viên {{employeeNo}}. Vui lòng hoàn thiện và gửi phê duyệt.",
                "Draft separation for employee {{employeeNo}}",
                "A draft separation ({{groundEn}}, last working day {{lastWorkingDate}}) was opened for employee "
                        + "{{employeeNo}}. Please complete it and submit it for approval.");
        return out;
    }

    private static void add(List<NotificationTemplateDefinition> out, String event, List<Placeholder> p,
            String subjectVi, String bodyVi, String subjectEn, String bodyEn) {
        out.add(new NotificationTemplateDefinition(event, "REMINDER", false, "IN_APP", "vi-VN", subjectVi, bodyVi,
                "TEXT", p, false));
        out.add(new NotificationTemplateDefinition(event, "REMINDER", false, "IN_APP", "en-US", subjectEn, bodyEn,
                "TEXT", p, false));
        out.add(new NotificationTemplateDefinition(event, "REMINDER", false, "EMAIL", "vi-VN", subjectVi,
                bodyVi + " {{deepLink}}", "TEXT", p, false));
        out.add(new NotificationTemplateDefinition(event, "REMINDER", false, "EMAIL", "en-US", subjectEn,
                bodyEn + " {{deepLink}}", "TEXT", p, false));
    }

    @Override
    public List<DocumentTypeDefinition> documentTypes() {
        return List.of(new DocumentTypeDefinition(SeparationKeys.EVIDENCE_DOC, "hr", SeparationKeys.SUBJECT_TYPE,
                "Hồ sơ chứng minh ngoại lệ chấm dứt hợp đồng", "Separation evidence (lawful exception)",
                "CONFIDENTIAL", "EMPLOYEE_FILE", PeoplePermissions.SEPARATION_VIEW, PeoplePermissions.SEPARATION_UPDATE,
                Set.of(), null));
    }

    @Override
    public Set<String> ownerEntityTypes() {
        return Set.of(SeparationKeys.SUBJECT_TYPE);
    }

    @Override
    @HrTransactional(readOnly = true)
    public Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId) {
        return db.jdbc().sql("select employment_id from hr.separation where tenant_id = :t and id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", ownerEntityId).query(UUID.class)
                .optional().flatMap(e -> access.target(e, clock.today())).map(AuthorizationTarget.class::cast);
    }
}
