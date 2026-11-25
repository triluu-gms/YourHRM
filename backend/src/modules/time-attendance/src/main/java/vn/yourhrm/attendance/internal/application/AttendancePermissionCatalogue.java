package vn.yourhrm.attendance.internal.application;

import static vn.yourhrm.shared.auth.api.PermissionAction.CREATE;
import static vn.yourhrm.shared.auth.api.PermissionAction.EXPORT;
import static vn.yourhrm.shared.auth.api.PermissionAction.IMPORT;
import static vn.yourhrm.shared.auth.api.PermissionAction.UPDATE;
import static vn.yourhrm.shared.auth.api.PermissionAction.VIEW;
import static vn.yourhrm.shared.auth.api.PermissionDefinition.of;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.shared.auth.api.PermissionCatalogue;
import vn.yourhrm.shared.auth.api.PermissionDefinition;

/** Time &amp; Attendance's permission catalogue, registered by Core into {@code core.permission}. */
@Component
class AttendancePermissionCatalogue implements PermissionCatalogue {

    @Override
    public String module() {
        return "att";
    }

    @Override
    public List<PermissionDefinition> permissions() {
        return List.of(
                of(AttendancePermissions.HOLIDAY_CALENDAR_VIEW, VIEW, "Xem lịch ngày lễ", "View holiday calendars"),
                of(AttendancePermissions.HOLIDAY_CALENDAR_UPDATE, UPDATE, "Cập nhật lịch ngày lễ",
                        "Change holiday calendars"),
                of(AttendancePermissions.SCHEDULE_CONFIG_VIEW, VIEW, "Xem ca và lịch làm việc",
                        "View shifts and schedule patterns"),
                of(AttendancePermissions.SCHEDULE_CONFIG_UPDATE, UPDATE, "Cấu hình ca và lịch làm việc",
                        "Configure shifts, schedule patterns and rules"),
                of(AttendancePermissions.SCHEDULE_ASSIGNMENT_VIEW, VIEW, "Xem phân lịch làm việc",
                        "View schedule assignments"),
                of(AttendancePermissions.SCHEDULE_ASSIGNMENT_UPDATE, UPDATE, "Phân lịch làm việc",
                        "Assign work schedules"),
                of(AttendancePermissions.DEVICE_VIEW, VIEW, "Xem máy chấm công và ánh xạ mã chấm công",
                        "View devices and enrolment mappings"),
                of(AttendancePermissions.DEVICE_UPDATE, UPDATE, "Cập nhật máy chấm công và ánh xạ mã chấm công",
                        "Change devices and enrolment mappings"),
                of(AttendancePermissions.PUNCH_VIEW, VIEW, "Xem dữ liệu chấm công thô", "View raw punches"),
                of(AttendancePermissions.PUNCH_IMPORT, IMPORT, "Import dữ liệu chấm công", "Import punch files"),
                of(AttendancePermissions.PUNCH_RESOLVE, UPDATE, "Xử lý lượt chấm bị cách ly",
                        "Resolve quarantined punches"),
                of(AttendancePermissions.TIMESHEET_VIEW, VIEW, "Xem bảng công ngày", "View daily timesheets"),
                of(AttendancePermissions.TIMESHEET_UPDATE, UPDATE, "Điều chỉnh và tính lại bảng công",
                        "Edit and recalculate timesheets"),
                of(AttendancePermissions.TIMESHEET_CORRECTION_REQUEST, CREATE, "Gửi yêu cầu điều chỉnh chấm công",
                        "Request attendance corrections"),
                of(AttendancePermissions.ATTENDANCE_CODE_VIEW, VIEW, "Xem ký hiệu chấm công",
                        "View attendance codes"),
                of(AttendancePermissions.ATTENDANCE_CODE_UPDATE, UPDATE, "Cấu hình ký hiệu chấm công",
                        "Configure attendance codes"),
                of(AttendancePermissions.OVERTIME_VIEW, VIEW, "Xem làm thêm giờ", "View overtime"),
                of(AttendancePermissions.OVERTIME_REQUEST, CREATE, "Đăng ký làm thêm giờ", "Request overtime"),
                of(AttendancePermissions.OVERTIME_CONSENT, UPDATE, "Xác nhận đồng ý làm thêm giờ",
                        "Consent to overtime"),
                of(AttendancePermissions.OVERTIME_CAP_OVERRIDE, UPDATE, "Vượt giới hạn làm thêm giờ có lý do",
                        "Override overtime caps with a reason"),
                of(AttendancePermissions.LEAVE_TYPE_VIEW, VIEW, "Xem loại nghỉ và chính sách nghỉ",
                        "View leave types and policies"),
                of(AttendancePermissions.LEAVE_TYPE_UPDATE, UPDATE, "Cấu hình loại nghỉ và chính sách nghỉ",
                        "Configure leave types and policies"),
                of(AttendancePermissions.LEAVE_REQUEST_VIEW, VIEW, "Xem đơn nghỉ", "View leave requests"),
                of(AttendancePermissions.LEAVE_REQUEST_CREATE, CREATE, "Tạo và rút đơn nghỉ",
                        "Create and withdraw leave requests"),
                of(AttendancePermissions.LEAVE_REQUEST_MANAGE, UPDATE, "Xử lý đơn nghỉ đã duyệt",
                        "Handle approved leave requests"),
                of(AttendancePermissions.LEAVE_SI_DATA_VIEW, VIEW, "Xem dữ liệu hồ sơ BHXH của đơn nghỉ",
                        "View SI claim data of leave requests"),
                of(AttendancePermissions.LEAVE_BALANCE_VIEW, VIEW, "Xem số dư phép", "View leave balances"),
                of(AttendancePermissions.LEAVE_BALANCE_ADJUST, UPDATE, "Điều chỉnh và tính số dư phép",
                        "Adjust and run leave balances"),
                of(AttendancePermissions.LEAVE_BALANCE_IMPORT, IMPORT, "Import số dư phép đầu kỳ",
                        "Import opening leave balances"),
                of(AttendancePermissions.PERIOD_VIEW, VIEW, "Xem kỳ chấm công và kết quả chấm công",
                        "View attendance periods and results"),
                of(AttendancePermissions.PERIOD_CLOSE, UPDATE, "Chốt kỳ chấm công", "Close attendance periods"),
                of(AttendancePermissions.PERIOD_REOPEN, UPDATE, "Mở lại kỳ chấm công đã chốt",
                        "Reopen closed attendance periods"),
                of(AttendancePermissions.PERIOD_HOLD, VIEW, "Nhận yêu cầu giải phóng kỳ chấm công đang giữ",
                        "Receive release requests of held periods"),
                of(AttendancePermissions.OVERTIME_IMPORT, IMPORT, "Import số giờ làm thêm lũy kế đầu kỳ",
                        "Import year-to-date overtime"),
                of(AttendancePermissions.ESS_VIEW, VIEW, "Xem chấm công của tôi", "View my attendance"),
                of(AttendancePermissions.REMINDER_RUN, UPDATE, "Chạy nhắc việc chấm công",
                        "Run attendance reminders"),
                of(AttendancePermissions.INTEGRATION_VIEW, VIEW, "Xem sự kiện tích hợp chấm công",
                        "View T&A integration inbox"),
                of(AttendancePermissions.INTEGRATION_RESYNC, UPDATE, "Đồng bộ lại dữ liệu tích hợp chấm công",
                        "Re-sync T&A integration data"),
                of(AttendancePermissions.TIMESHEET_IMPORT, IMPORT, "Import bảng công ngày", "Import daily timesheets"),
                of(AttendancePermissions.RESULT_IMPORT, IMPORT, "Import kết quả chấm công kỳ",
                        "Import the attendance result"),
                of(AttendancePermissions.JOB_RUN, UPDATE, "Chạy job đêm của chấm công", "Run the T&A nightly job"),
                of(AttendancePermissions.REPORT_EXPORT, EXPORT, "Xuất báo cáo chấm công ra Excel",
                        "Export T&A reports to Excel"));
    }
}
