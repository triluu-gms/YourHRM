package vn.yourhrm.people.internal.application.employment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * Process types of the employment stream: {@code hr.HIRE} (US-PPL-019) and {@code hr.PRIMARY_EMPLOYMENT_CHANGE}
 * (US-PPL-022). The vendor default chain is one HR approval step (role HR_ADMIN in scope); tenants configure their
 * own definitions in Core. Summaries carry no P3 value and no salary amount.
 */
@Configuration(proxyBeanMethods = false)
class EmploymentCatalogues {

    @Bean
    ProcessTypeCatalogue employmentProcessTypes() {
        return new ProcessTypeCatalogue() {
            @Override
            public String module() {
                return "hr";
            }

            @Override
            public List<ProcessTypeDefinition> processTypes() {
                StepTemplate hr = new StepTemplate("hr", "Nhân sự phê duyệt", "HR approval",
                        ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", "HR_ADMIN"), true);
                return List.of(
                        new ProcessTypeDefinition(EmploymentKeys.HIRE_PROCESS, "Tuyển dụng", "Hire",
                                Map.of("type", "object"), List.of("hireType", "workerCategoryCode"),
                                Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO), false, false, List.of(hr)),
                        new ProcessTypeDefinition(EmploymentKeys.PRIMARY_CHANGE_PROCESS,
                                "Thay đổi nơi làm việc chính (giảm trừ gia cảnh)",
                                "Primary employment change (PIT family deduction)",
                                Map.of("type", "object"), List.of("reasonCode"),
                                Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO), false, false, List.of(hr)));
            }
        };
    }
}
