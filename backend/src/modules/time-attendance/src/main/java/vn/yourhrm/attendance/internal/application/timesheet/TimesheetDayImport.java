package vn.yourhrm.attendance.internal.application.timesheet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.punch.PunchImportSupport;
import vn.yourhrm.attendance.internal.application.timesheet.CorrectionService.ImportedDay;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Daily-timesheet import ({@code ATT_TIMESHEET_DAY}, BR-ATT-025 / 037, US-ATT-018) for sites without a device (R2-06):
 * one row per employee and day with an attendance code and / or the in / out times (local time of the employee's
 * location; an out time not after the in time is the next day) and a reason. Each row becomes a correction of source
 * IMPORT recorded with the import job as its batch ({@link CorrectionService#importDay}); daily processing applies it
 * on every recalculation, so the day is marked as manually overridden and is never edited in place. Days of a CLOSED
 * or LOCKED period are refused (reopen first). Rows are independent: partial commit, each in its own T&amp;A
 * transaction, idempotent on a re-run of the same values. Rollback of a committed job is not supported (the
 * corrections are evidence; a wrong day is fixed by importing or editing it again).
 */
@Component
class TimesheetDayImport implements ImportType {

    static final String CODE = "ATT_TIMESHEET_DAY";

    private final PunchImportSupport s;
    private final CorrectionService corrections;

    TimesheetDayImport(PunchImportSupport support, CorrectionService corrections) {
        this.s = support;
        this.corrections = corrections;
    }

    @Override
    public Definition definition() {
        return new Definition(CODE, "att", "Bảng công ngày", "Daily timesheets", "1",
                AttendancePermissions.TIMESHEET_IMPORT, List.of(
                        Column.of("employee_no", "Mã nhân viên", "Employee number", ColumnType.TEXT, true),
                        Column.of("work_date", "Ngày công", "Work date", ColumnType.DATE, true),
                        new Column("code", "Ký hiệu chấm công", "Attendance code", ColumnType.TEXT, false, null, false,
                                "Ví dụ X, CT, WFH; để trống khi chỉ nhập giờ vào / ra",
                                "E.g. X, CT, WFH; leave empty when only the in / out times are given"),
                        new Column("time_in", "Giờ vào", "Time in", ColumnType.TEXT, false, null, false,
                                "HH:mm, giờ địa phương", "HH:mm, local time"),
                        new Column("time_out", "Giờ ra", "Time out", ColumnType.TEXT, false, null, false,
                                "HH:mm; nhỏ hơn hoặc bằng giờ vào = ngày hôm sau",
                                "HH:mm; not after the time in = the next day"),
                        Column.of("reason", "Lý do", "Reason", ColumnType.TEXT, true)),
                List.of("employee_no", "work_date"), true, false, List.of(), List.of());
    }

    @Override
    public List<RowMessage> validate(ImportContext ctx, ImportRow row) {
        Map<String, Object> st = s.state(ctx, row, "V");
        List<RowMessage> out = new ArrayList<>();
        Parsed p = parse(ctx, row, st, out);
        if (p == null) {
            return out;
        }
        out.addAll(s.dryRun(() -> {
            ImportedDay d = write(ctx, p);
            List<RowMessage> w = new ArrayList<>();
            if (!d.created()) {
                w.add(RowMessage.warning(null, "already-imported", "Ngày công đã được import với cùng giá trị; bỏ qua",
                        "The day was imported with the same values before; skipped"));
            }
            return w;
        }));
        return out;
    }

    @Override
    public CommitResult commit(ImportContext ctx, ImportRow row) {
        Map<String, Object> st = s.state(ctx, row, "C");
        List<RowMessage> out = new ArrayList<>();
        Parsed p = parse(ctx, row, st, out);
        if (p == null) {
            throw ApiException.unprocessable(out.getFirst().code(), out.getFirst().messageEn());
        }
        ImportedDay d = s.write(() -> write(ctx, p));
        return d.created() ? CommitResult.created("att.timesheet_correction", d.correctionId())
                : CommitResult.updated("att.timesheet_correction", d.correctionId());
    }

    // ------------------------------------------------------------------------------------------------ rows

    private record Parsed(UUID employmentId, LocalDate workDate, String code, LocalTime in, LocalTime out,
            String reason) {
    }

    private Parsed parse(ImportContext ctx, ImportRow row, Map<String, Object> st, List<RowMessage> out) {
        String no = row.text("employee_no");
        UUID emp = no == null ? null : s.employmentByNumber(ctx, st, no);
        if (emp == null) {
            out.add(RowMessage.error("employee_no", "employee-not-found", "Không tìm thấy nhân viên " + no
                    + " của pháp nhân trong phạm vi dữ liệu", "Employee " + no
                    + " is not an employee of the job's legal entity in your scope"));
            return null;
        }
        LocalDate date = row.date("work_date");
        LocalTime in = time(row, "time_in", out);
        LocalTime outT = time(row, "time_out", out);
        if (!out.isEmpty()) {
            return null;
        }
        return new Parsed(emp, date, row.text("code"), in, outT, row.text("reason"));
    }

    private static LocalTime time(ImportRow row, String key, List<RowMessage> out) {
        String v = row.text(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(v.strip().length() == 4 ? "0" + v.strip() : v.strip());
        } catch (DateTimeParseException e) {
            out.add(RowMessage.error(key, "invalid-time", "Giờ không hợp lệ: " + v + " (định dạng HH:mm)",
                    "Invalid time: " + v + " (format HH:mm)"));
            return null;
        }
    }

    private ImportedDay write(ImportContext ctx, Parsed p) {
        Instant in = null;
        Instant out = null;
        if (p.in() != null || p.out() != null) {
            ZoneId zone = corrections.zoneOf(p.employmentId(), p.workDate());
            if (p.in() != null) {
                in = p.workDate().atTime(p.in()).atZone(zone).toInstant();
            }
            if (p.out() != null) {
                LocalDate outDay = p.in() != null && !p.out().isAfter(p.in()) ? p.workDate().plusDays(1)
                        : p.workDate();
                out = outDay.atTime(p.out()).atZone(zone).toInstant();
            }
        }
        return corrections.importDay(p.employmentId(), p.workDate(), p.code(), in, out, p.reason(), ctx.jobId());
    }
}
