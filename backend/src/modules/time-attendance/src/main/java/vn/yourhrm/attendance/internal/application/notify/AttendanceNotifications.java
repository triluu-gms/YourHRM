package vn.yourhrm.attendance.internal.application.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;

/**
 * T&amp;A's notifications through Core (BR-ATT-082) and the settings of the reminders. Request submitted / approved /
 * rejected are Core's workflow notifications ({@code core.WorkflowTaskAssigned}, the final decision to the requester)
 * and are not repeated here. Placeholders are dates and numbers only; the record is behind the deep link
 * (BR-CORE-074). Every template exists in-app and by e-mail, in Vietnamese and English.
 */
@Component
public class AttendanceNotifications implements NotificationTemplateCatalogue, SettingCatalogue {

    /** To the employee, the morning after a day with a missing check-in / check-out. */
    public static final String MISSING_PUNCH = "att.MissingPunchReminder";
    /** To the employee and the holders of {@code att.overtime.cap_override}: the monthly cap threshold is reached. */
    public static final String OT_MONTH_CAP = "att.OvertimeMonthCapThreshold";
    /** Same for the annual cap. */
    public static final String OT_YEAR_CAP = "att.OvertimeYearCapThreshold";
    /** To the employee: an HR manual schedule assignment changes their schedule from a date (P1 "phân ca"). */
    public static final String SCHEDULE_CHANGED = "att.ScheduleChanged";
    /** To the employee: carried leave days expire soon (default 30 days before). */
    public static final String CARRY_EXPIRING = "att.LeaveCarryExpiring";
    /** To the holders of {@code att.period.close} of the legal entity: the close deadline of a period is near. */
    public static final String CLOSE_DUE = "att.PeriodCloseDue";

    /** Send the missing-punch reminders of the legal entity (BR-ATT-082). */
    public static final String SETTING_MISSING_PUNCH = "att.reminder_missing_punch";
    /** Days before the expiry of carried leave the employee is reminded (0 = off). */
    public static final String SETTING_CARRY_DAYS = "att.reminder_carry_expiry_days";
    /** Days before a period's close deadline the T&amp;A administrators are reminded (0 = off). */
    public static final String SETTING_CLOSE_DAYS = "att.reminder_close_days";

    private static final Placeholder WORK_DATE = new Placeholder("workDate", Kind.DATE);
    private static final Placeholder FROM_DATE = new Placeholder("fromDate", Kind.DATE);
    private static final Placeholder PERCENT = new Placeholder("percent", Kind.NUMBER);
    private static final Placeholder DAYS = new Placeholder("days", Kind.NUMBER);
    private static final Placeholder EXPIRES = new Placeholder("expiresOn", Kind.DATE);
    private static final Placeholder START = new Placeholder("periodStart", Kind.DATE);
    private static final Placeholder END = new Placeholder("periodEnd", Kind.DATE);
    private static final Placeholder DEADLINE = new Placeholder("closeDeadline", Kind.DATE);

    @Override
    public String module() {
        return "att";
    }

    @Override
    public List<NotificationTemplateDefinition> templates() {
        List<NotificationTemplateDefinition> out = new ArrayList<>();
        add(out, MISSING_PUNCH, List.of(WORK_DATE),
                "Thiếu lượt chấm công ngày {{workDate}}",
                "Bảng công ngày {{workDate}} của bạn thiếu giờ vào hoặc giờ ra. Vui lòng gửi yêu cầu điều chỉnh "
                        + "chấm công nếu bạn đã đi làm.",
                "Missing punch on {{workDate}}",
                "Your timesheet of {{workDate}} misses a check-in or check-out. Please request an attendance "
                        + "correction if you worked that day.");
        add(out, OT_MONTH_CAP, List.of(PERCENT, WORK_DATE),
                "Làm thêm giờ đạt {{percent}}% giới hạn tháng",
                "Số giờ làm thêm đã duyệt và đang chờ duyệt trong tháng của ngày {{workDate}} đã đạt {{percent}}% "
                        + "giới hạn làm thêm giờ tháng.",
                "Overtime at {{percent}}% of the monthly cap",
                "Approved and pending overtime in the month of {{workDate}} has reached {{percent}}% of the "
                        + "monthly overtime cap.");
        add(out, OT_YEAR_CAP, List.of(PERCENT, WORK_DATE),
                "Làm thêm giờ đạt {{percent}}% giới hạn năm",
                "Số giờ làm thêm đã duyệt và đang chờ duyệt trong năm của ngày {{workDate}} đã đạt {{percent}}% "
                        + "giới hạn làm thêm giờ năm.",
                "Overtime at {{percent}}% of the annual cap",
                "Approved and pending overtime in the year of {{workDate}} has reached {{percent}}% of the "
                        + "annual overtime cap.");
        add(out, SCHEDULE_CHANGED, List.of(FROM_DATE),
                "Lịch làm việc của bạn thay đổi từ {{fromDate}}",
                "Lịch làm việc của bạn đã được cập nhật, có hiệu lực từ ngày {{fromDate}}. Xem lịch làm việc mới "
                        + "trong mục Chấm công của tôi.",
                "Your work schedule changes from {{fromDate}}",
                "Your work schedule was updated, effective {{fromDate}}. See the new schedule in My attendance.");
        add(out, CARRY_EXPIRING, List.of(DAYS, EXPIRES),
                "{{days}} ngày phép chuyển sang sắp hết hạn",
                "{{days}} ngày phép năm chuyển sang của bạn sẽ hết hạn vào ngày {{expiresOn}} nếu không được sử dụng.",
                "{{days}} carried leave days expire soon",
                "{{days}} of your carried annual leave days expire on {{expiresOn}} if they are not taken.");
        add(out, CLOSE_DUE, List.of(START, END, DEADLINE),
                "Hạn chốt kỳ chấm công {{periodStart}} - {{periodEnd}}: {{closeDeadline}}",
                "Kỳ chấm công {{periodStart}} - {{periodEnd}} cần được chốt trước ngày {{closeDeadline}}. Vui lòng "
                        + "xử lý danh sách ngoại lệ và chốt kỳ.",
                "Attendance period {{periodStart}} - {{periodEnd}} closes by {{closeDeadline}}",
                "The attendance period {{periodStart}} - {{periodEnd}} is due to be closed by {{closeDeadline}}. "
                        + "Please resolve the exception checklist and close the period.");
        return out;
    }

    private static void add(List<NotificationTemplateDefinition> out, String code, List<Placeholder> ph,
            String subjectVi, String bodyVi, String subjectEn, String bodyEn) {
        out.add(new NotificationTemplateDefinition(code, "REMINDER", false, "IN_APP", "vi-VN", subjectVi, bodyVi,
                "TEXT", ph, false));
        out.add(new NotificationTemplateDefinition(code, "REMINDER", false, "IN_APP", "en-US", subjectEn, bodyEn,
                "TEXT", ph, false));
        out.add(new NotificationTemplateDefinition(code, "REMINDER", false, "EMAIL", "vi-VN", subjectVi,
                bodyVi + " {{deepLink}}", "TEXT", ph, false));
        out.add(new NotificationTemplateDefinition(code, "REMINDER", false, "EMAIL", "en-US", subjectEn,
                bodyEn + " {{deepLink}}", "TEXT", ph, false));
    }

    @Override
    public List<SettingDefinition> settings() {
        Set<String> levels = Set.of("TENANT", "LEGAL_ENTITY");
        return List.of(
                new SettingDefinition(SETTING_MISSING_PUNCH, "BOOL", List.of(), true, levels, true, false,
                        "Nhắc nhân viên thiếu lượt chấm công vào sáng hôm sau",
                        "Remind employees of missing punches the next morning"),
                new SettingDefinition(SETTING_CARRY_DAYS, "INT", List.of(), 30, levels, true, false,
                        "Số ngày nhắc trước khi phép chuyển sang hết hạn (0 = không nhắc)",
                        "Days before carried leave expires to remind the employee (0 = off)"),
                new SettingDefinition(SETTING_CLOSE_DAYS, "INT", List.of(), 2, levels, true, false,
                        "Số ngày nhắc trước hạn chốt kỳ chấm công (0 = không nhắc)",
                        "Days before the close deadline to remind T&A administrators (0 = off)"));
    }
}
