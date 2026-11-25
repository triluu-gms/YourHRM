package vn.yourhrm.attendance.internal.application.ess;

import java.util.Locale;
import java.util.Map;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Vietnamese / English labels of the fixed T&amp;A vocabularies shown in ESS (BR-ATT-080): day types, day statuses,
 * timesheet exceptions and request statuses. Attendance codes and leave types carry their own tenant labels.
 */
final class EssLabels {

    private EssLabels() {
    }

    /** {@code vi} or {@code en}: the requested language, else the user's locale, else Vietnamese. */
    static String lang(String requested) {
        String l = requested;
        if (l == null || l.isBlank()) {
            l = TenantContext.optional().map(TenantContext::locale).orElse(null);
        }
        return l != null && l.toLowerCase(Locale.ROOT).startsWith("en") ? "en" : "vi";
    }

    private record Label(String vi, String en) {
    }

    private static final Map<String, Label> DAY_TYPES = Map.of(
            "WORKDAY", new Label("Ngày làm việc", "Working day"),
            "REST_DAY", new Label("Ngày nghỉ hằng tuần", "Weekly rest day"),
            "PUBLIC_HOLIDAY", new Label("Ngày lễ", "Public holiday"),
            "SUBSTITUTE_HOLIDAY", new Label("Ngày nghỉ bù lễ", "Substitute holiday"),
            "COMPANY_HOLIDAY", new Label("Ngày nghỉ của công ty", "Company holiday"));

    private static final Map<String, Label> DAY_STATUSES = Map.ofEntries(
            Map.entry("SCHEDULED", new Label("Chưa có dữ liệu chấm công", "Scheduled")),
            Map.entry("PRESENT", new Label("Có mặt", "Present")),
            Map.entry("PARTIAL", new Label("Có mặt một phần", "Partly present")),
            Map.entry("INCOMPLETE", new Label("Thiếu lượt chấm", "Incomplete punches")),
            Map.entry("ABSENT", new Label("Vắng mặt", "Absent")),
            Map.entry("ON_LEAVE", new Label("Nghỉ phép", "On leave")),
            Map.entry("HOLIDAY", new Label("Nghỉ lễ", "Holiday")),
            Map.entry("REST", new Label("Nghỉ", "Rest")),
            Map.entry("NOT_EMPLOYED", new Label("Ngoài thời gian làm việc", "Not employed")),
            Map.entry("SUSPENDED", new Label("Tạm hoãn hợp đồng", "Contract suspended")));

    private static final Map<String, Label> EXCEPTIONS = Map.of(
            "MISSING_IN", new Label("Thiếu giờ vào", "Missing check-in"),
            "MISSING_OUT", new Label("Thiếu giờ ra", "Missing check-out"),
            "ABSENT_NO_CODE", new Label("Vắng mặt không có ký hiệu", "Absent without a code"),
            "LATE", new Label("Đi muộn", "Late arrival"),
            "EARLY", new Label("Về sớm", "Early departure"),
            "QUARANTINED_PUNCH", new Label("Lượt chấm chưa được ghép", "Unmatched punch"),
            "OT_CAP", new Label("Vượt giới hạn làm thêm", "Overtime cap exceeded"));

    private static final Map<String, Label> STATUSES = Map.of(
            "DRAFT", new Label("Nháp", "Draft"),
            "PENDING", new Label("Chờ duyệt", "Pending approval"),
            "APPROVED", new Label("Đã duyệt", "Approved"),
            "REJECTED", new Label("Bị từ chối", "Rejected"),
            "CANCELLED", new Label("Đã hủy", "Cancelled"),
            "WITHDRAWN", new Label("Đã rút", "Withdrawn"),
            "APPLIED", new Label("Đã áp dụng", "Applied"));

    private static final Map<String, Label> KINDS = Map.of(
            "LEAVE", new Label("Đơn nghỉ", "Leave request"),
            "OVERTIME", new Label("Đăng ký làm thêm giờ", "Overtime request"),
            "CORRECTION", new Label("Điều chỉnh chấm công", "Attendance correction"));

    static String dayType(String code, String lang) {
        return of(DAY_TYPES, code, lang);
    }

    static String dayStatus(String code, String lang) {
        return of(DAY_STATUSES, code, lang);
    }

    static String exception(String code, String lang) {
        return of(EXCEPTIONS, code, lang);
    }

    static String status(String code, String lang) {
        return of(STATUSES, code, lang);
    }

    static String kind(String code, String lang) {
        return of(KINDS, code, lang);
    }

    static String pick(String vi, String en, String lang) {
        return "en".equals(lang) && en != null && !en.isBlank() ? en : vi;
    }

    /** Unknown codes are shown as the code itself. */
    private static String of(Map<String, Label> m, String code, String lang) {
        if (code == null) {
            return null;
        }
        Label l = m.get(code);
        return l == null ? code : pick(l.vi(), l.en(), lang);
    }
}
