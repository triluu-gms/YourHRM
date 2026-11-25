package vn.yourhrm.attendance.internal.application.punch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.domain.punch.PunchParsing;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.shared.auth.api.AuthorizationService;

/**
 * The punch-file layouts registered with Core's import engine (US-ATT-016/017). Core maps a file's header row to the
 * columns by key or by the VI / EN header text (accent- and case-insensitive), so a device-export layout lists the
 * vendor's own header texts. Vendor layouts are {@code [high-uncertainty]}: the pilot's device models and export
 * formats are not collected yet (backlog US-ATT-017); tenant-defined column mappings need a layout table in the DDL
 * and are not built.
 */
final class PunchImports {

    private PunchImports() {
    }

    /** The standard Excel template: device (optional), enrol id or employee number, date, time, direction. */
    @Component
    static class Standard extends PunchImport {

        static final String CODE = "ATT_PUNCH_EXCEL";

        Standard(PunchImportSupport s, PunchStore store, AuthorizationService authz) {
            super(s, store, authz);
        }

        @Override
        protected String layoutCode() {
            return "STANDARD";
        }

        @Override
        public Definition definition() {
            return definition(CODE, "Dữ liệu chấm công (mẫu Excel chuẩn)", "Punches (standard Excel template)",
                    List.of(text("terminal", "Máy chấm công", "Device", false,
                                    "Mã máy hoặc mã terminal đã đăng ký; để trống khi nhập theo mã nhân viên",
                                    "Registered device or terminal code; empty for rows by employee number"),
                            text("enrol_id", "Mã chấm công", "Enrol ID", false,
                                    "Mã người dùng trên máy chấm công (hoặc điền mã nhân viên)",
                                    "User id on the device (or give the employee number)"),
                            text("employee_no", "Mã nhân viên", "Employee number", false,
                                    "Dùng khi không có mã chấm công; cần chọn pháp nhân và ngày hiệu lực của lô",
                                    "When there is no enrol id; the job needs a legal entity and as-of date"),
                            Column.of("punch_date", "Ngày chấm", "Punch date", ColumnType.DATE, true),
                            text("punch_time", "Giờ chấm", "Punch time", true, "HH:mm hoặc HH:mm:ss (giờ địa phương"
                                    + " của máy chấm công)", "HH:mm or HH:mm:ss (the device's local time)"),
                            text("direction", "Vào/Ra", "In/Out", false, "VÀO / RA / IN / OUT; để trống nếu không rõ",
                                    "IN / OUT; empty when unknown"),
                            text("verify_method", "Phương thức xác thực", "Verify method", false,
                                    "Vân tay / Khuôn mặt / Thẻ / Mật khẩu", "Fingerprint / Face / Card / Password")));
        }

        @Override
        protected Line read(ImportRow row, List<RowMessage> out) {
            String enrol = trimmed(row, "enrol_id");
            String employeeNo = trimmed(row, "employee_no");
            if (enrol != null && employeeNo != null) {
                out.add(RowMessage.error("employee_no", "enrol-id-or-employee-no",
                        "Chỉ điền mã chấm công hoặc mã nhân viên, không điền cả hai",
                        "Give the enrol id or the employee number, not both"));
                return null;
            }
            LocalDate date = row.date("punch_date");
            LocalTime time;
            try {
                time = PunchParsing.time(row.text("punch_time"));
            } catch (RuntimeException e) {
                out.add(RowMessage.error("punch_time", "invalid-time", "Giờ không hợp lệ: '" + row.text("punch_time")
                        + "' (HH:mm hoặc HH:mm:ss)", "Invalid time '" + row.text("punch_time") + "' (HH:mm or HH:mm:ss)"));
                return null;
            }
            return new Line(trimmed(row, "terminal"), enrol, employeeNo, LocalDateTime.of(date, time),
                    direction(row, "direction", out), PunchParsing.verifyMethod(row.text("verify_method")));
        }
    }

    /**
     * ZKTeco attendance-software export (ZKTime / ZKBioTime "original records"): {@code AC-No.} / {@code EnNo}, a
     * date-time column {@code Time} / {@code DateTime}, {@code State} ("C/In", "C/Out", 0..5), an optional machine
     * column and verify mode.
     */
    @Component
    static class ZkTeco extends PunchImport {

        static final String CODE = "ATT_PUNCH_ZKTECO";

        ZkTeco(PunchImportSupport s, PunchStore store, AuthorizationService authz) {
            super(s, store, authz);
        }

        @Override
        protected String layoutCode() {
            return "ZKTECO";
        }

        @Override
        public Definition definition() {
            List<Column> cols = new ArrayList<>(List.of(
                    text("enrol_id", "AC-No.", "EnNo", true, "Mã người dùng trên máy", "User id on the device"),
                    text("date_time", "Time", "DateTime", true, "Ngày giờ chấm theo giờ của máy",
                            "Punch date and time, device clock"),
                    text("direction", "State", "In/Out", false, "C/In, C/Out, 0..5", "C/In, C/Out, 0..5"),
                    text("terminal", "Machine", "TMNo", false, "Mã máy (nếu có cột này)", "Machine number, if any"),
                    text("verify_method", "Verify Mode", "Mode", false, null, null)));
            cols.addAll(refusedColumns());
            return definition(CODE, "Dữ liệu chấm công xuất từ ZKTeco", "Punches exported by ZKTeco software", cols);
        }

        @Override
        protected Line read(ImportRow row, List<RowMessage> out) {
            LocalDateTime at = dateTime(row, "date_time", out);
            if (at == null) {
                return null;
            }
            return new Line(trimmed(row, "terminal"), trimmed(row, "enrol_id"), null, at,
                    direction(row, "direction", out), PunchParsing.verifyMethod(row.text("verify_method")));
        }
    }

    /**
     * Hikvision iVMS-4200 / HikCentral attendance export: {@code Person ID} / {@code Employee ID}, a date-time
     * column, {@code Attendance Status} ("Check-in", "Check-out"), device name and authentication type.
     */
    @Component
    static class Hikvision extends PunchImport {

        static final String CODE = "ATT_PUNCH_HIKVISION";

        Hikvision(PunchImportSupport s, PunchStore store, AuthorizationService authz) {
            super(s, store, authz);
        }

        @Override
        protected String layoutCode() {
            return "HIKVISION";
        }

        @Override
        public Definition definition() {
            List<Column> cols = new ArrayList<>(List.of(
                    text("enrol_id", "Person ID", "Employee ID", true, "Mã người trên thiết bị",
                            "Person id on the device"),
                    text("date_time", "Authentication Time", "Time", true, "Ngày giờ chấm theo giờ của thiết bị",
                            "Punch date and time, device clock"),
                    text("direction", "Attendance Status", "Authentication Status", false, "Check-in / Check-out",
                            "Check-in / Check-out"),
                    text("terminal", "Device Name", "Attendance Check Point", false, "Mã terminal đã đăng ký",
                            "Registered terminal code"),
                    text("verify_method", "Authentication Type", "Verify Mode", false, null, null)));
            cols.addAll(refusedColumns());
            return definition(CODE, "Dữ liệu chấm công xuất từ Hikvision", "Punches exported by Hikvision software",
                    cols);
        }

        @Override
        protected Line read(ImportRow row, List<RowMessage> out) {
            LocalDateTime at = dateTime(row, "date_time", out);
            if (at == null) {
                return null;
            }
            return new Line(trimmed(row, "terminal"), trimmed(row, "enrol_id"), null, at,
                    direction(row, "direction", out), PunchParsing.verifyMethod(row.text("verify_method")));
        }
    }
}
