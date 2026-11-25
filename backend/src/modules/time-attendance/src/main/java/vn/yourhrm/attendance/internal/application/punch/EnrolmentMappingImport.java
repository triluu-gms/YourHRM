package vn.yourhrm.attendance.internal.application.punch;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.punch.EnrolmentMappingService.MappingInput;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.DeviceRow;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Enrolment mappings from Excel (US-ATT-014): device (empty = every device), enrol id, employee number, first day and
 * last day (INCLUSIVE in the file, stored exclusive). Idempotent by device scope + enrol id + first day: an existing
 * version of the same employment gets the file's end; of another employment is an error. All rows or none.
 * Employee numbers are resolved in the job's legal entity on the job's as-of date.
 */
@Component
class EnrolmentMappingImport implements ImportType {

    static final String CODE = "ATT_ENROLMENT_MAPPING";

    private final PunchImportSupport s;
    private final PunchStore store;
    private final EnrolmentMappingService mappings;

    EnrolmentMappingImport(PunchImportSupport s, PunchStore store, EnrolmentMappingService mappings) {
        this.s = s;
        this.store = store;
        this.mappings = mappings;
    }

    @Override
    public Definition definition() {
        return new Definition(CODE, "att", "Ánh xạ mã chấm công", "Enrolment mappings", PunchImport.TEMPLATE_VERSION,
                AttendancePermissions.DEVICE_UPDATE, List.of(
                        PunchImport.text("terminal", "Máy chấm công", "Device", false,
                                "Mã máy hoặc mã terminal; để trống = áp dụng cho mọi máy",
                                "Device or terminal code; empty = every device"),
                        PunchImport.text("enrol_id", "Mã chấm công", "Enrol ID", true, "Mã người dùng trên máy",
                                "User id on the device"),
                        PunchImport.text("employee_no", "Mã nhân viên", "Employee number", true, null, null),
                        Column.of("valid_from", "Từ ngày", "Valid from", ColumnType.DATE, true),
                        new Column("valid_to", "Đến hết ngày", "Valid until (inclusive)", ColumnType.DATE, false, null,
                                false, "Để trống = không thời hạn", "Empty = open-ended")),
                List.of("terminal", "enrol_id", "valid_from"), false, false, List.of(), List.of());
    }

    @Override
    public List<RowMessage> validate(ImportContext ctx, ImportRow row) {
        Map<String, Object> st = s.state(ctx, row, "V");
        List<RowMessage> out = new ArrayList<>();
        out.addAll(s.dryRun(() -> {
            write(ctx, row, st);
            return List.of();
        }));
        return out;
    }

    @Override
    public CommitResult commit(ImportContext ctx, ImportRow row) {
        Map<String, Object> st = s.state(ctx, row, "C");
        return s.write(() -> write(ctx, row, st));
    }

    private CommitResult write(ImportContext ctx, ImportRow row, Map<String, Object> st) {
        String terminal = PunchImport.trimmed(row, "terminal");
        UUID deviceId = null;
        if (terminal != null) {
            DeviceRow d = store.deviceByTerminalOrCode(terminal).orElseThrow(() -> ApiException.unprocessable(
                    "unknown-device", "Device " + terminal + " is not registered"));
            deviceId = d.id();
        }
        String employeeNo = PunchImport.trimmed(row, "employee_no");
        UUID employment = s.employmentByNumber(ctx, st, employeeNo);
        if (employment == null) {
            throw ApiException.unprocessable("employee-not-found", "Employee " + employeeNo
                    + " is not an employee of the job's legal entity in your scope on the job's as-of date");
        }
        LocalDate from = row.date("valid_from");
        LocalDate last = row.date("valid_to");
        if (last != null && last.isBefore(from)) {
            throw ApiException.unprocessable("invalid-period", "The last day is before the first day");
        }
        LocalDate to = last == null ? null : last.plusDays(1);
        String enrol = PunchImport.trimmed(row, "enrol_id");
        var existing = mappings.sameStart(deviceId, enrol, from);
        if (existing.isPresent()) {
            var m = existing.get();
            if (!m.span().employmentId().equals(employment)) {
                throw ApiException.conflict("enrolment-mapping-overlap", "Enrol id " + enrol
                        + " is already mapped to another employee from " + from);
            }
            if (!java.util.Objects.equals(m.span().validTo(), to)) {
                mappings.end(m.span().id(), m.version(), to);
            }
            return CommitResult.updated("att.enrolment_mapping", m.span().id());
        }
        UUID id = mappings.createChecked(new MappingInput(deviceId, enrol, employment, from, to), "IMPORT");
        return CommitResult.created("att.enrolment_mapping", id);
    }
}
