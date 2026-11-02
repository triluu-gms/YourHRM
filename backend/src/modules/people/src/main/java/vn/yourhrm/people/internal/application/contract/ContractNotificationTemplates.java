package vn.yourhrm.people.internal.application.contract;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;

/**
 * People's alert templates of the contract stream (US-PPL-059, BR-PPL-052/054/056): in-app + e-mail, VI and EN.
 * Placeholders are the employee number, contract number and dates only: no name, no personal data, no amount
 * (BR-CORE-074); the record is behind the deep link. The offsets are tenant settings ({@link ContractSettings}).
 */
@Component
public class ContractNotificationTemplates implements NotificationTemplateCatalogue {

    public static final String CONTRACT_EXPIRING = "hr.ContractExpiring";
    public static final String AUTO_CONVERSION_DUE = "hr.ContractAutoConversionDue";
    public static final String PROBATION_ENDING = "hr.ProbationEnding";
    public static final String SUSPENSION_RETURN_DUE = "hr.SuspensionReturnDue";

    private static final Placeholder EMPLOYEE_NO = new Placeholder("employeeNo", Kind.TEXT);
    private static final Placeholder CONTRACT_NO = new Placeholder("contractNo", Kind.TEXT);
    private static final Placeholder END_DATE = new Placeholder("endDate", Kind.DATE);
    private static final Placeholder DAYS_LEFT = new Placeholder("daysLeft", Kind.NUMBER);
    private static final Placeholder DUE_DATE = new Placeholder("dueDate", Kind.DATE);

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<NotificationTemplateDefinition> templates() {
        List<NotificationTemplateDefinition> out = new ArrayList<>();
        List<Placeholder> expiring = List.of(EMPLOYEE_NO, CONTRACT_NO, END_DATE, DAYS_LEFT);
        add(out, CONTRACT_EXPIRING, expiring,
                "Hợp đồng {{contractNo}} sắp hết hạn",
                "Hợp đồng lao động {{contractNo}} của nhân viên {{employeeNo}} hết hạn ngày {{endDate}} (còn {{daysLeft}} ngày). "
                        + "Vui lòng gia hạn, chuyển đổi hoặc thông báo không gia hạn.",
                "Contract {{contractNo}} is expiring",
                "Labour contract {{contractNo}} of employee {{employeeNo}} ends on {{endDate}} ({{daysLeft}} days left). "
                        + "Please renew, convert or give notice of non-renewal.");
        add(out, AUTO_CONVERSION_DUE, List.of(EMPLOYEE_NO, CONTRACT_NO, END_DATE),
                "Hợp đồng {{contractNo}} có thể đã chuyển thành không xác định thời hạn",
                "Nhân viên {{employeeNo}} vẫn làm việc sau khi hợp đồng {{contractNo}} hết hạn ngày {{endDate}} mà chưa có "
                        + "hợp đồng mới; theo luật hợp đồng có thể đã trở thành hợp đồng không xác định thời hạn.",
                "Contract {{contractNo}} may have become indefinite",
                "Employee {{employeeNo}} is still working after contract {{contractNo}} ended on {{endDate}} without a new "
                        + "contract; by law it may have become an indefinite contract.");
        add(out, PROBATION_ENDING, List.of(EMPLOYEE_NO, CONTRACT_NO, END_DATE, DAYS_LEFT),
                "Thử việc của nhân viên {{employeeNo}} sắp kết thúc",
                "Thời gian thử việc ({{contractNo}}) của nhân viên {{employeeNo}} kết thúc ngày {{endDate}} (còn {{daysLeft}} "
                        + "ngày). Vui lòng ghi nhận và thông báo kết quả thử việc trước ngày này.",
                "Probation of employee {{employeeNo}} is ending",
                "The probation ({{contractNo}}) of employee {{employeeNo}} ends on {{endDate}} ({{daysLeft}} days left). "
                        + "Please record and communicate the probation result before then.");
        add(out, SUSPENSION_RETURN_DUE, List.of(EMPLOYEE_NO, CONTRACT_NO, END_DATE, DUE_DATE),
                "Nhân viên {{employeeNo}} cần trở lại làm việc",
                "Thời gian tạm hoãn hợp đồng {{contractNo}} của nhân viên {{employeeNo}} đã kết thúc ngày {{endDate}}; "
                        + "nhân viên phải có mặt trở lại làm việc chậm nhất ngày {{dueDate}}. Vui lòng ghi nhận ngày trở lại.",
                "Employee {{employeeNo}} is due back at work",
                "The suspension of contract {{contractNo}} of employee {{employeeNo}} ended on {{endDate}}; the employee "
                        + "must return to work by {{dueDate}}. Please record the return date.");
        return out;
    }

    private static void add(List<NotificationTemplateDefinition> out, String event, List<Placeholder> placeholders,
            String subjectVi, String bodyVi, String subjectEn, String bodyEn) {
        out.add(new NotificationTemplateDefinition(event, "REMINDER", false, "IN_APP", "vi-VN", subjectVi, bodyVi,
                "TEXT", placeholders, false));
        out.add(new NotificationTemplateDefinition(event, "REMINDER", false, "IN_APP", "en-US", subjectEn, bodyEn,
                "TEXT", placeholders, false));
        out.add(new NotificationTemplateDefinition(event, "REMINDER", false, "EMAIL", "vi-VN", subjectVi,
                bodyVi + " {{deepLink}}", "TEXT", placeholders, false));
        out.add(new NotificationTemplateDefinition(event, "REMINDER", false, "EMAIL", "en-US", subjectEn,
                bodyEn + " {{deepLink}}", "TEXT", placeholders, false));
    }
}
