package vn.yourhrm.people.internal.application.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * What the bank-account stream registers with Core: the approval process type of bank changes (US-PPL-032/075), the
 * e-mail / in-app notification to the employee when an ESS bank change is decided (US-PPL-075) and the step-up
 * settings of ESS bank changes (NFR-PPL-011).
 */
@Component
public class BankCatalogues implements ProcessTypeCatalogue, NotificationTemplateCatalogue, SettingCatalogue {

    public static final String PROCESS_BANK_ACCOUNT_CHANGE = "hr.BANK_ACCOUNT_CHANGE";
    public static final String SUBJECT_TYPE = "hr.bank_account";

    public static final String EVENT_CHANGED = "hr.BankAccountChanged";
    public static final String EVENT_CHANGE_REJECTED = "hr.BankAccountChangeRejected";

    /** Accepted {@code acr} values of a step-up authenticated token (JSON list). */
    public static final String SETTING_STEP_UP_ACR = "hr.ess_bank_step_up_acr_values";
    /** Maximum age in seconds of the step-up authentication ({@code auth_time}) when the request is submitted. */
    public static final String SETTING_STEP_UP_MAX_AGE = "hr.ess_bank_step_up_max_age_seconds";

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<ProcessTypeDefinition> processTypes() {
        return List.of(new ProcessTypeDefinition(PROCESS_BANK_ACCOUNT_CHANGE, "Thay đổi tài khoản nhận lương",
                "Salary bank account change",
                Map.of("type", "object", "properties", Map.of(
                        "bankCode", Map.of("type", "string"),
                        "splitType", Map.of("type", "string"),
                        "validFrom", Map.of("type", "string"),
                        "channel", Map.of("type", "string", "enum", List.of("HR", "ESS")))),
                List.of("channel", "splitType"), Set.of(WorkflowAction.RETURN, WorkflowAction.REQUEST_INFO), false,
                false,
                List.of(new StepTemplate("cb", "C&B xác minh", "C&B verification",
                        ApproverResolver.of(ApproverResolver.Type.ROLE_IN_SCOPE, "roleCode", "HR_ADMIN"), true))));
    }

    @Override
    public List<NotificationTemplateDefinition> templates() {
        List<NotificationTemplateDefinition> out = new ArrayList<>();
        List<Placeholder> p = List.of(new Placeholder("bankName", Kind.TEXT), new Placeholder("validFrom", Kind.TEXT));
        add(out, EVENT_CHANGED, p,
                "Tài khoản nhận lương đã thay đổi",
                "Tài khoản nhận lương mới tại {{bankName}} của bạn đã được duyệt, áp dụng từ {{validFrom}}. "
                        + "Nếu bạn không yêu cầu thay đổi này, hãy liên hệ phòng nhân sự ngay.",
                "Xem chi tiết: {{deepLink}}",
                "Your salary bank account changed",
                "Your new salary account at {{bankName}} was approved and applies from {{validFrom}}. "
                        + "If you did not request this change, contact HR immediately.",
                "View the details: {{deepLink}}");
        add(out, EVENT_CHANGE_REJECTED, p,
                "Yêu cầu đổi tài khoản nhận lương bị từ chối",
                "Yêu cầu đổi tài khoản nhận lương sang {{bankName}} (từ {{validFrom}}) của bạn không được duyệt.",
                "Xem chi tiết: {{deepLink}}",
                "Salary bank account change rejected",
                "Your request to change the salary account to {{bankName}} (from {{validFrom}}) was rejected.",
                "View the details: {{deepLink}}");
        return out;
    }

    private static void add(List<NotificationTemplateDefinition> out, String event, List<Placeholder> p,
            String subjectVi, String bodyVi, String linkVi, String subjectEn, String bodyEn, String linkEn) {
        out.add(new NotificationTemplateDefinition(event, "SECURITY", true, "IN_APP", "vi-VN", subjectVi, bodyVi,
                "TEXT", p, false));
        out.add(new NotificationTemplateDefinition(event, "SECURITY", true, "IN_APP", "en-US", subjectEn, bodyEn,
                "TEXT", p, false));
        out.add(new NotificationTemplateDefinition(event, "SECURITY", true, "EMAIL", "vi-VN", subjectVi,
                bodyVi + "\n\n" + linkVi, "TEXT", p, false));
        out.add(new NotificationTemplateDefinition(event, "SECURITY", true, "EMAIL", "en-US", subjectEn,
                bodyEn + "\n\n" + linkEn, "TEXT", p, false));
    }

    @Override
    public List<SettingDefinition> settings() {
        return List.of(
                new SettingDefinition(SETTING_STEP_UP_ACR, "JSON", List.of(), List.of("step-up", "mfa"),
                        Set.of("TENANT"), false, false,
                        "Giá trị acr của phiên xác thực tăng cường được chấp nhận khi nhân viên đổi tài khoản ngân hàng",
                        "acr values accepted as step-up authentication for ESS bank account changes"),
                new SettingDefinition(SETTING_STEP_UP_MAX_AGE, "INT", List.of(), 300, Set.of("TENANT"), false, false,
                        "Thời gian tối đa (giây) từ lần xác thực tăng cường đến khi gửi yêu cầu đổi tài khoản",
                        "Maximum age in seconds of the step-up authentication when an ESS bank change is submitted"));
    }
}
