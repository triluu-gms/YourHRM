package vn.yourhrm.attendance.internal.application.overtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * What overtime registers with Core: the approval process type (BR-ATT-040; the vendor default is one line-manager
 * step, tenants add levels through their own definition, e.g. an HR step when {@code capOverride} is true) and the
 * tenant policy settings per legal entity.
 */
@Component
public class OvertimeCatalogues implements ProcessTypeCatalogue, SettingCatalogue {

    public static final String PROCESS_OT_REQUEST = "att.OT_REQUEST";
    public static final String SUBJECT_TYPE = "att.overtime_request";

    /** BR-ATT-045: WARN or BLOCK when normal + OT hours exceed the daily limit. */
    public static final String SETTING_DAILY_CAP_MODE = "att.ot_daily_cap_mode";
    /** BR-ATT-046: warning threshold in percent of the monthly / annual cap. */
    public static final String SETTING_CAP_WARN_PERCENT = "att.ot_cap_warn_percent";
    /** BR-ATT-046/049: annual regime of the legal entity, STANDARD_200 or EXTENDED_300 (eligible industries). */
    public static final String SETTING_ANNUAL_REGIME = "att.ot_annual_regime";
    /** BR-ATT-049: reference of the notice to the provincial labour authority (required for EXTENDED_300). */
    public static final String SETTING_REGIME_NOTICE_REF = "att.ot_regime_notice_ref";
    /** BR-ATT-044: pay approved overtime as worked (employees exempt from time recording). */
    public static final String SETTING_PAID_AS_APPROVED = "att.ot_paid_as_approved";

    @Override
    public String module() {
        return "att";
    }

    @Override
    public List<ProcessTypeDefinition> processTypes() {
        return List.of(new ProcessTypeDefinition(PROCESS_OT_REQUEST, "Đăng ký làm thêm giờ", "Overtime request",
                Map.of("type", "object", "properties", Map.of(
                        "workDate", Map.of("type", "string"),
                        "start", Map.of("type", "string"),
                        "end", Map.of("type", "string"),
                        "plannedMinutes", Map.of("type", "integer"),
                        "reason", Map.of("type", "string"),
                        "capOverride", Map.of("type", "boolean"),
                        "protectedWorker", Map.of("type", "boolean"))),
                List.of("plannedMinutes", "capOverride", "protectedWorker"),
                Set.of(WorkflowAction.REQUEST_INFO, WorkflowAction.FORWARD, WorkflowAction.BULK_APPROVE), false,
                false,
                List.of(new StepTemplate("manager", "Quản lý trực tiếp", "Line manager",
                        ApproverResolver.of(ApproverResolver.Type.LINE_MANAGER), false))));
    }

    @Override
    public List<SettingDefinition> settings() {
        Set<String> levels = Set.of("TENANT", "LEGAL_ENTITY");
        return List.of(
                new SettingDefinition(SETTING_DAILY_CAP_MODE, "ENUM", List.of("WARN", "BLOCK"), "BLOCK", levels, true,
                        false, "Vượt giới hạn giờ làm việc trong ngày (giờ bình thường + làm thêm): cảnh báo hay chặn",
                        "Daily limit of normal + overtime hours exceeded: warn or block"),
                new SettingDefinition(SETTING_CAP_WARN_PERCENT, "INT", List.of(), 80, levels, true, false,
                        "Ngưỡng cảnh báo (%) của giới hạn làm thêm giờ tháng / năm",
                        "Warning threshold in percent of the monthly / annual overtime cap"),
                new SettingDefinition(SETTING_ANNUAL_REGIME, "ENUM", List.of("STANDARD_200", "EXTENDED_300"),
                        "STANDARD_200", levels, true, false,
                        "Chế độ giới hạn làm thêm giờ năm của pháp nhân (300 giờ chỉ cho ngành nghề đủ điều kiện)",
                        "Annual overtime regime of the legal entity (300 hours for eligible industries only)"),
                new SettingDefinition(SETTING_REGIME_NOTICE_REF, "TEXT", List.of(), "", levels, true, false,
                        "Số văn bản thông báo cơ quan quản lý lao động cấp tỉnh về làm thêm đến 300 giờ/năm",
                        "Reference of the notice to the provincial labour authority for up to 300 overtime hours"),
                new SettingDefinition(SETTING_PAID_AS_APPROVED, "BOOL", List.of(), false, levels, true, false,
                        "Trả làm thêm giờ theo số giờ đã duyệt (nhân viên miễn chấm công)",
                        "Pay overtime as approved (employees exempt from time recording)"));
    }
}
