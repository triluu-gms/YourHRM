package vn.yourhrm.attendance.internal.application.report;

import java.util.Map;
import vn.yourhrm.attendance.internal.application.ess.EssLabels;

/**
 * Vietnamese / English labels of the reports (BRD 03 §8): column headers, payroll categories, exception types,
 * checklist items and the signature block. Day statuses, exceptions and request statuses come from
 * {@link EssLabels}; attendance codes and leave types carry their own tenant labels.
 */
final class ReportLabels {

    private ReportLabels() {
    }

    private record Label(String vi, String en) {
    }

    static String lang(String requested) {
        return EssLabels.lang(requested);
    }

    private static final Map<String, Label> TEXT = Map.ofEntries(
            // report titles
            Map.entry("R01", new Label("BẢNG CHẤM CÔNG", "TIMESHEET")),
            Map.entry("R04", new Label("BÁO CÁO TỔNG HỢP LÀM THÊM GIỜ", "OVERTIME SUMMARY")),
            Map.entry("R06", new Label("BÁO CÁO SỐ DƯ NGÀY PHÉP", "LEAVE BALANCE REPORT")),
            // common headers
            Map.entry("no", new Label("STT", "No.")),
            Map.entry("employeeNo", new Label("Mã NV", "Employee no.")),
            Map.entry("fullName", new Label("Họ và tên", "Full name")),
            Map.entry("month", new Label("Tháng", "Month")),
            Map.entry("period", new Label("Kỳ", "Period")),
            Map.entry("from", new Label("Từ ngày", "From")),
            Map.entry("to", new Label("Đến ngày", "To")),
            Map.entry("asOf", new Label("Tại ngày", "As of")),
            Map.entry("unit", new Label("Đơn vị", "Unit")),
            Map.entry("total", new Label("Tổng cộng", "Total")),
            Map.entry("legend", new Label("Ký hiệu chấm công", "Attendance codes")),
            // R-01 totals
            Map.entry("workedHours", new Label("Giờ làm việc", "Worked hours")),
            Map.entry("otHours", new Label("Giờ làm thêm", "Overtime hours")),
            Map.entry("nightHours", new Label("Giờ làm đêm", "Night hours")),
            Map.entry("lateMin", new Label("Phút đi muộn", "Late minutes")),
            Map.entry("earlyMin", new Label("Phút về sớm", "Early minutes")),
            // payroll categories (BRD §7.3)
            Map.entry("WORKED_DAYS", new Label("Ngày công làm việc", "Worked days")),
            Map.entry("PAID_LEAVE_DAYS", new Label("Nghỉ hưởng lương", "Paid leave days")),
            Map.entry("HOLIDAY_DAYS", new Label("Nghỉ lễ, Tết", "Holiday days")),
            Map.entry("SI_LEAVE_DAYS", new Label("Nghỉ hưởng BHXH", "SI leave days")),
            Map.entry("UNPAID_DAYS", new Label("Nghỉ không lương", "Unpaid days")),
            Map.entry("NOT_EMPLOYED_DAYS", new Label("Ngoài thời gian làm việc", "Not employed days")),
            Map.entry("UNMAPPED", new Label("Ký hiệu chưa phân loại", "Unmapped codes")),
            // R-02 exception types
            Map.entry("LATE", new Label("Đi muộn", "Late arrival")),
            Map.entry("EARLY", new Label("Về sớm", "Early departure")),
            Map.entry("MISSING_PUNCH", new Label("Thiếu lượt chấm", "Missing punch")),
            Map.entry("ABSENT", new Label("Vắng mặt", "Absent")),
            Map.entry("TOTAL_DAYS", new Label("Số ngày có ngoại lệ", "Days with exceptions")),
            // R-03 validation statuses
            Map.entry("VALID", new Label("Hợp lệ", "Valid")),
            Map.entry("QUARANTINED_UNMAPPED", new Label("Cách ly: chưa ánh xạ mã chấm công", "Quarantined: unmapped")),
            Map.entry("QUARANTINED_EXPIRED", new Label("Cách ly: ánh xạ hết hiệu lực", "Quarantined: mapping expired")),
            Map.entry("QUARANTINED_UNKNOWN_DEVICE", new Label("Cách ly: máy chấm công chưa đăng ký",
                    "Quarantined: unknown device")),
            Map.entry("IGNORED", new Label("Đã bỏ qua", "Ignored")),
            Map.entry("IMPORT", new Label("Import tệp", "File import")),
            // R-04 / R-05
            Map.entry("monthUsed", new Label("Giờ làm thêm trong tháng", "Month overtime hours")),
            Map.entry("monthCap", new Label("Giới hạn tháng (giờ)", "Month cap (hours)")),
            Map.entry("monthPct", new Label("% giới hạn tháng", "% of month cap")),
            Map.entry("yearUsed", new Label("Lũy kế từ đầu năm", "Year-to-date hours")),
            Map.entry("yearCap", new Label("Giới hạn năm (giờ)", "Year cap (hours)")),
            Map.entry("yearPct", new Label("% giới hạn năm", "% of year cap")),
            Map.entry("regime", new Label("Chế độ giới hạn năm", "Annual regime")),
            Map.entry("STANDARD_200", new Label("200 giờ/năm", "200 hours/year")),
            Map.entry("EXTENDED_300", new Label("300 giờ/năm", "300 hours/year")),
            Map.entry("OT-WD", new Label("Ngày thường", "Working day")),
            Map.entry("OT-RD", new Label("Ngày nghỉ hằng tuần", "Rest day")),
            Map.entry("OT-PH", new Label("Ngày lễ, Tết", "Public holiday")),
            Map.entry("OT-WD-N", new Label("Đêm ngày thường", "Working day, night")),
            Map.entry("OT-RD-N", new Label("Đêm ngày nghỉ hằng tuần", "Rest day, night")),
            Map.entry("OT-PH-N", new Label("Đêm ngày lễ, Tết", "Public holiday, night")),
            // R-06
            Map.entry("leaveType", new Label("Loại nghỉ", "Leave type")),
            Map.entry("leaveYear", new Label("Năm phép", "Leave year")),
            Map.entry("opening", new Label("Đầu kỳ", "Opening")),
            Map.entry("carriedIn", new Label("Chuyển từ năm trước", "Carried in")),
            Map.entry("accrued", new Label("Tích lũy", "Accrued")),
            Map.entry("taken", new Label("Đã nghỉ", "Taken")),
            Map.entry("adjusted", new Label("Điều chỉnh", "Adjusted")),
            Map.entry("carriedOut", new Label("Chuyển sang năm sau", "Carried out")),
            Map.entry("expired", new Label("Hết hạn", "Expired")),
            Map.entry("encashed", new Label("Thanh toán tiền", "Paid out")),
            Map.entry("closing", new Label("Cuối kỳ", "Closing")),
            // R-08 checklist
            Map.entry("STD_METHOD_MISSING", new Label("Chưa có phương pháp ngày công chuẩn", "Standard-day method missing")),
            Map.entry("PERIOD_NOT_ENDED", new Label("Kỳ chưa kết thúc", "Period not ended")),
            Map.entry("NOT_PROCESSED", new Label("Nhân viên chưa được tính công", "Employments not processed")),
            Map.entry("ABSENT_NO_CODE", new Label("Vắng mặt không có ký hiệu", "Absent without a code")),
            Map.entry("QUARANTINED_PUNCH", new Label("Lượt chấm bị cách ly", "Quarantined punches")),
            Map.entry("OT_CAP", new Label("Vượt giới hạn làm thêm", "Overtime cap exceeded")),
            Map.entry("PENDING_LEAVE", new Label("Đơn nghỉ chờ duyệt", "Pending leave requests")),
            Map.entry("PENDING_OVERTIME", new Label("Đăng ký làm thêm chờ duyệt", "Pending overtime requests")),
            Map.entry("PENDING_CORRECTION", new Label("Điều chỉnh công chờ duyệt", "Pending corrections")),
            Map.entry("OPEN", new Label("Đang mở", "Open")),
            Map.entry("IN_REVIEW", new Label("Đang rà soát", "In review")),
            Map.entry("CLOSED", new Label("Đã chốt", "Closed")),
            Map.entry("LOCKED", new Label("Đã khóa (đã tính lương)", "Locked (payroll approved)")),
            // signature block (VN layout)
            Map.entry("preparedBy", new Label("Người lập", "Prepared by")),
            Map.entry("headOfUnit", new Label("Trưởng bộ phận", "Head of department")),
            Map.entry("director", new Label("Giám đốc", "Director")),
            Map.entry("signHint", new Label("(Ký, ghi rõ họ tên)", "(Signature, full name)")),
            Map.entry("directorHint", new Label("(Ký, họ tên, đóng dấu)", "(Signature, full name, seal)")),
            Map.entry("dateLine", new Label("Ngày ..... tháng ..... năm ..........", "Date: ...................")));

    /** The label of a key; unknown keys (codes) are returned as they are. */
    static String text(String key, String lang) {
        if (key == null) {
            return null;
        }
        Label l = TEXT.get(key);
        return l == null ? key : EssLabels.pick(l.vi(), l.en(), lang);
    }

    static String pick(String vi, String en, String lang) {
        return EssLabels.pick(vi, en, lang);
    }

    static String exception(String code, String lang) {
        return EssLabels.exception(code, lang);
    }

    static String dayStatus(String code, String lang) {
        return EssLabels.dayStatus(code, lang);
    }

    static String status(String code, String lang) {
        return EssLabels.status(code, lang);
    }

    /** Short weekday of the timesheet header: T2..T7, CN (vi) or Mo..Su (en). */
    static String weekday(java.time.DayOfWeek d, String lang) {
        if ("en".equals(lang)) {
            return d.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH).substring(0, 2);
        }
        return d == java.time.DayOfWeek.SUNDAY ? "CN" : "T" + (d.getValue() + 1);
    }
}
