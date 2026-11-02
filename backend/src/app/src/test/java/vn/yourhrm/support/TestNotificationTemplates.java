package vn.yourhrm.support;

import java.util.List;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;

/**
 * Templates of a pretend Payroll module (tests only): {@code pay.TEST_PAYSLIP} is the registered attachment
 * exception (R2-17), {@code pay.TEST_REMINDER} is an ordinary event.
 */
public class TestNotificationTemplates implements NotificationTemplateCatalogue {

    public static final String PAYSLIP = "pay.TEST_PAYSLIP";
    public static final String REMINDER = "pay.TEST_REMINDER";

    @Override
    public String module() {
        return "pay";
    }

    @Override
    public List<NotificationTemplateDefinition> templates() {
        List<Placeholder> period = List.of(new Placeholder("period", Kind.TEXT));
        return List.of(
                new NotificationTemplateDefinition(PAYSLIP, "PAYSLIP", true, "IN_APP", "vi-VN", "Phiếu lương {{period}}",
                        "Phiếu lương kỳ {{period}} đã sẵn sàng.", "TEXT", period, false),
                new NotificationTemplateDefinition(PAYSLIP, "PAYSLIP", true, "IN_APP", "en-US", "Payslip {{period}}",
                        "Your payslip for {{period}} is ready.", "TEXT", period, false),
                new NotificationTemplateDefinition(PAYSLIP, "PAYSLIP", true, "EMAIL", "vi-VN", "Phiếu lương {{period}}",
                        "<p>Phiếu lương kỳ {{period}} đã sẵn sàng: <a href=\"{{deepLink}}\">xem</a></p>", "HTML", period,
                        true),
                new NotificationTemplateDefinition(REMINDER, "REMINDER", false, "IN_APP", "vi-VN", "Nhắc việc",
                        "Hạn chốt {{period}}.", "TEXT", period, false),
                new NotificationTemplateDefinition(REMINDER, "REMINDER", false, "EMAIL", "vi-VN", "Nhắc việc",
                        "Hạn chốt {{period}}: {{deepLink}}", "TEXT", period, false));
    }
}
