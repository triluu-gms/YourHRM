package vn.yourhrm.attendance.internal.application.approval;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * The approval process types of HR actions on attendance data (ATT-P-11): {@code att.PERIOD_REOPEN} (reopen a CLOSED
 * period, BR-ATT-073) and {@code att.BALANCE_ADJUST} (a manual leave balance movement, BR-ATT-068), and the settings
 * per legal entity that switch them on. The default of both settings is false: the action runs at once, as before.
 * When an approval is required the request is stored in {@code att.change_request}, Core's workflow decides, and the
 * action runs on the final approval ({@link ChangeRequestWorkflowListener}).
 */
@Component
public class ApprovalCatalogues implements ProcessTypeCatalogue, SettingCatalogue {

    public static final String PROCESS_PERIOD_REOPEN = "att.PERIOD_REOPEN";
    public static final String PROCESS_BALANCE_ADJUST = "att.BALANCE_ADJUST";
    public static final String SUBJECT_CHANGE_REQUEST = "att.change_request";

    /** BR-ATT-073 / ATT-P-11: a reopen of a CLOSED period needs an approval through Core's workflow. */
    public static final String SETTING_REOPEN_APPROVAL = "att.period_reopen_approval";
    /** BR-ATT-068 / ATT-P-11: a leave balance adjustment needs an approval through Core's workflow. */
    public static final String SETTING_ADJUST_APPROVAL = "att.leave_adjust_approval";

    private final Settings settings;
    private final BusinessClock clock;

    public ApprovalCatalogues(Settings settings, BusinessClock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public String module() {
        return "att";
    }

    /** True when the setting asks for an approval for the legal entity today (missing / unreadable = false). */
    public boolean required(String setting, UUID legalEntityId) {
        try {
            return settings.booleanValue(setting, new Settings.Scope(legalEntityId, null), clock.today());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public List<ProcessTypeDefinition> processTypes() {
        return List.of(
                new ProcessTypeDefinition(PROCESS_PERIOD_REOPEN, "Mở lại kỳ chấm công", "Attendance period reopen",
                        Map.of("type", "object", "properties", Map.of(
                                "periodStart", Map.of("type", "string"),
                                "periodEnd", Map.of("type", "string"),
                                "currentVersion", Map.of("type", "integer"),
                                "heldByPayroll", Map.of("type", "boolean"),
                                "reason", Map.of("type", "string"))),
                        List.of("heldByPayroll"), Set.of(WorkflowAction.REQUEST_INFO), false, false,
                        // default: an HR administrator of the legal entity; tenants change it in Core's designer
                        List.of(new StepTemplate("hr", "HR duyệt", "HR approval",
                                ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", "HR_ADMIN"),
                                true))),
                new ProcessTypeDefinition(PROCESS_BALANCE_ADJUST, "Điều chỉnh số dư phép",
                        "Leave balance adjustment",
                        Map.of("type", "object", "properties", Map.of(
                                "leaveType", Map.of("type", "string"),
                                "leaveYear", Map.of("type", "integer"),
                                "quantity", Map.of("type", "number"),
                                "reason", Map.of("type", "string"))),
                        List.of("leaveType", "quantity"), Set.of(WorkflowAction.REQUEST_INFO), false, false,
                        List.of(new StepTemplate("hr", "HR duyệt", "HR approval",
                                ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", "HR_ADMIN"),
                                true))));
    }

    @Override
    public List<SettingDefinition> settings() {
        Set<String> levels = Set.of("TENANT", "LEGAL_ENTITY");
        return List.of(
                new SettingDefinition(SETTING_REOPEN_APPROVAL, "BOOL", List.of(), false, levels, true, false,
                        "Mở lại kỳ chấm công đã chốt cần phê duyệt qua quy trình",
                        "Reopening a closed attendance period needs a workflow approval"),
                new SettingDefinition(SETTING_ADJUST_APPROVAL, "BOOL", List.of(), false, levels, true, false,
                        "Điều chỉnh số dư phép cần phê duyệt qua quy trình",
                        "Leave balance adjustments need a workflow approval"));
    }
}
