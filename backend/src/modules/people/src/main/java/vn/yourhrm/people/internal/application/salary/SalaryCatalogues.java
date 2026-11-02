package vn.yourhrm.people.internal.application.salary;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/** Catalogue beans of the salary stream: process type {@code hr.SALARY_CHANGE} (US-PPL-042) and its settings. */
@Configuration(proxyBeanMethods = false)
class SalaryCatalogues {

    static final String SALARY_CHANGE = "hr.SALARY_CHANGE";

    @Bean
    ProcessTypeCatalogue salaryProcessTypes() {
        return new ProcessTypeCatalogue() {
            @Override
            public String module() {
                return "hr";
            }

            @Override
            public List<ProcessTypeDefinition> processTypes() {
                // The summary carries no amounts (approvers without hr.compensation.view must not see them).
                return List.of(new ProcessTypeDefinition(SALARY_CHANGE, "Thay đổi lương", "Salary change",
                        Map.of("type", "object"), List.of("actionCode", "correction"),
                        Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO), false, false,
                        List.of(new StepTemplate("hr", "Nhân sự phê duyệt", "HR approval",
                                ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", "HR_ADMIN"),
                                true))));
            }
        };
    }

    @Bean
    SettingCatalogue salarySettings() {
        return new SettingCatalogue() {
            @Override
            public String module() {
                return "hr";
            }

            @Override
            public List<SettingDefinition> settings() {
                return List.of(new SettingDefinition(CompensationPurposes.SESSION_MINUTES, "INT", List.of(), 480,
                        Set.of("TENANT"), false, false,
                        "Thời gian (phút) ghi nhớ mục đích xem lương trong một phiên",
                        "Minutes a stated purpose of viewing salary data is remembered for the session"));
            }
        };
    }
}
