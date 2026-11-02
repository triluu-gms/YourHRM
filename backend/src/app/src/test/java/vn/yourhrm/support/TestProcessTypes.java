package vn.yourhrm.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * Process types of a pretend T&A module (tests only): {@code att.TEST_LEAVE} (line manager, then the HR of the
 * legal entity; return, request-info, forward and bulk approve allowed) and {@code att.TEST_OT} (line manager only,
 * no bulk approval, condition field {@code otType}).
 */
public class TestProcessTypes implements ProcessTypeCatalogue {

    public static final String LEAVE = "att.TEST_LEAVE";
    public static final String OVERTIME = "att.TEST_OT";
    public static final String HR_ROLE = "HR_LE";

    @Override
    public String module() {
        return "att";
    }

    @Override
    public List<ProcessTypeDefinition> processTypes() {
        return List.of(
                new ProcessTypeDefinition(LEAVE, "Nghỉ phép (test)", "Leave (test)", Map.of("type", "object"),
                        List.of("leaveType"), Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO,
                                WorkflowAction.FORWARD, WorkflowAction.BULK_APPROVE), false, false,
                        List.of(new StepTemplate("manager", "Quản lý trực tiếp", "Line manager",
                                        ApproverResolver.of(ApproverResolver.Type.LINE_MANAGER), true),
                                new StepTemplate("hr", "Nhân sự pháp nhân", "Legal entity HR",
                                        ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", HR_ROLE),
                                        true))),
                new ProcessTypeDefinition(OVERTIME, "Làm thêm giờ (test)", "Overtime (test)", Map.of(),
                        List.of("otType"), Set.of(WorkflowAction.RETURN), false, false,
                        List.of(new StepTemplate("manager", "Quản lý trực tiếp", "Line manager",
                                ApproverResolver.of(ApproverResolver.Type.LINE_MANAGER), true))));
    }

    /** The release job's part (the runtime role cannot write the global catalogue): register the rows as superuser. */
    public static void register(JdbcTemplate admin) {
        admin.update("""
                insert into core.process_type (process_type_code, owner_module, name_vi, name_en, condition_fields,
                                               allowed_actions)
                values ('att.TEST_LEAVE', 'att', 'Nghỉ phép (test)', 'Leave (test)', '["leaveType"]',
                        ARRAY['APPROVE','BULK_APPROVE','COMMENT','FORWARD','REJECT','REQUEST_INFO','RETURN','WITHDRAW']),
                       ('att.TEST_OT', 'att', 'Làm thêm giờ (test)', 'Overtime (test)', '["otType"]',
                        ARRAY['APPROVE','COMMENT','REJECT','RETURN','WITHDRAW'])
                on conflict do nothing""");
    }
}
