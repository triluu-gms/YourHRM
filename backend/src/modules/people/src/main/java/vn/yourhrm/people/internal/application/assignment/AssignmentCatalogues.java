package vn.yourhrm.people.internal.application.assignment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.people.internal.domain.assignment.ChangeReason;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * Catalogue beans of the assignment stream, registered by Core at start-up: the process types of job changes
 * (US-PPL-036) and the tenant setting holding the change-reason overrides (US-PPL-035).
 */
@Configuration(proxyBeanMethods = false)
class AssignmentCatalogues {

    /**
     * Tenant overrides of the change-reason catalogue (JSON array). Each entry has a {@code code}; an entry with a
     * default code changes that reason (e.g. {@code {"code":"MANAGER_CHANGE","active":false}}), any other code adds
     * a tenant reason ({@code nameVi, nameEn, actionCode, assignmentType, processTypeCode, decisionTypeCode,
     * documentKind, active}).
     */
    static final String CHANGE_REASONS = "hr.assignment.change_reasons";

    /** Vendor default approval: the HR Admin of the request's scope (tenants refine it in Core's workflow designer). */
    private static List<StepTemplate> hrApproval() {
        return List.of(new StepTemplate("hr", "Nhân sự phê duyệt", "HR approval",
                ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", "HR_ADMIN"), true));
    }

    @Bean
    ProcessTypeCatalogue assignmentProcessTypes() {
        return new ProcessTypeCatalogue() {
            @Override
            public String module() {
                return "hr";
            }

            @Override
            public List<ProcessTypeDefinition> processTypes() {
                return List.of(
                        new ProcessTypeDefinition(ChangeReason.JOB_CHANGE, "Thay đổi công việc", "Job change",
                                Map.of("type", "object"), List.of("reasonCode", "actionCode"),
                                Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO), false, false,
                                hrApproval()),
                        new ProcessTypeDefinition(ChangeReason.TEMPORARY_TRANSFER, "Điều chuyển tạm thời",
                                "Temporary transfer", Map.of("type", "object"), List.of("reasonCode"),
                                Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO), false, false,
                                hrApproval()));
            }
        };
    }

    @Bean
    SettingCatalogue assignmentSettings() {
        return new SettingCatalogue() {
            @Override
            public String module() {
                return "hr";
            }

            @Override
            public List<SettingDefinition> settings() {
                return List.of(new SettingDefinition(CHANGE_REASONS, "JSON", List.of(), List.of(), Set.of("TENANT"),
                        false, false, "Lý do thay đổi công việc của tenant (ghi đè / bổ sung danh mục mặc định)",
                        "Tenant job-change reasons (overrides / additions to the default catalogue)"));
            }
        };
    }
}
