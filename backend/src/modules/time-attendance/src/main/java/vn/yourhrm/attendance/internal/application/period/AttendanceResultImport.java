package vn.yourhrm.attendance.internal.application.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.period.ResultImportService.Row;
import vn.yourhrm.attendance.internal.application.punch.PunchImportSupport;
import vn.yourhrm.core.api.CoreEvents.ImportCommitted;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Attendance Result import ({@code ATT_RESULT_IMPORT}, BR-ATT-025 / 076, R2-18, US-ATT-019): one row per employee,
 * period and category (BRD §7.3 codes such as {@code STD_DAYS}, {@code WORKED_DAYS}, {@code PAID_LEAVE_DAYS.ANNUAL},
 * {@code OT_HOURS.OT-WD}) with the quantity, the unit (inferred from {@code *_DAYS} / {@code *_HOURS}) and, for
 * STD_DAYS / STD_HOURS, the optional full-period value. All-or-nothing: Core commits every row or none; the rows are
 * staged by {@link ResultImportService#stage} and, once Core's commit is done ({@code core.ImportCommitted}), published
 * as one CLOSED IMPORT version per period ({@link ResultImportService#publish}). Rollback of a committed job is not
 * supported: a wrong result is replaced by reopening the period and importing again.
 */
@Component
class AttendanceResultImport implements ImportType {

    static final String CODE = "ATT_RESULT_IMPORT";
    private static final Logger log = LoggerFactory.getLogger(AttendanceResultImport.class);

    private final PunchImportSupport s;
    private final ResultImportService service;

    AttendanceResultImport(PunchImportSupport support, ResultImportService service) {
        this.s = support;
        this.service = service;
    }

    @Override
    public Definition definition() {
        return new Definition(CODE, "att", "Kết quả chấm công kỳ", "Attendance result", "1",
                AttendancePermissions.RESULT_IMPORT, List.of(
                        Column.of("employee_no", "Mã nhân viên", "Employee number", ColumnType.TEXT, true),
                        Column.of("period_start", "Ngày bắt đầu kỳ", "Period start", ColumnType.DATE, true),
                        new Column("category", "Mã loại", "Category", ColumnType.TEXT, true, null, false,
                                "Mã theo BRD §7.3, ví dụ STD_DAYS, WORKED_DAYS, PAID_LEAVE_DAYS.ANNUAL, OT_HOURS.OT-WD",
                                "BRD §7.3 code, e.g. STD_DAYS, WORKED_DAYS, PAID_LEAVE_DAYS.ANNUAL, OT_HOURS.OT-WD"),
                        Column.of("quantity", "Số lượng", "Quantity", ColumnType.DECIMAL, true),
                        new Column("unit", "Đơn vị", "Unit", ColumnType.TEXT, false, null, false,
                                "DAY, HOUR, MINUTE, COUNT; mặc định theo mã loại (*_DAYS / *_HOURS)",
                                "DAY, HOUR, MINUTE, COUNT; default from the category (*_DAYS / *_HOURS)"),
                        new Column("period_quantity", "Giá trị cả kỳ", "Full-period value", ColumnType.DECIMAL, false,
                                null, false, "Chỉ cho STD_DAYS / STD_HOURS (giá trị của cả kỳ khi có phân đoạn)",
                                "STD_DAYS / STD_HOURS only (the whole period's value)")),
                List.of("employee_no", "period_start", "category"), false, false, List.of(), List.of());
    }

    @Override
    public List<RowMessage> validate(ImportContext ctx, ImportRow row) {
        Map<String, Object> st = s.state(ctx, row, "V");
        List<RowMessage> out = new ArrayList<>();
        Row r = parse(ctx, row, st, out);
        if (r == null) {
            return out;
        }
        out.addAll(s.dryRun(() -> {
            service.stage(r);
            return List.of();
        }));
        return out;
    }

    @Override
    public CommitResult commit(ImportContext ctx, ImportRow row) {
        Map<String, Object> st = s.state(ctx, row, "C");
        List<RowMessage> out = new ArrayList<>();
        Row r = parse(ctx, row, st, out);
        if (r == null) {
            throw ApiException.unprocessable(out.getFirst().code(), out.getFirst().messageEn());
        }
        UUID id = s.write(() -> service.stage(r));
        return CommitResult.created("att.attendance_result_import_line", id);
    }

    /** After Core committed the job: its staged rows become the result versions (one transaction). */
    @EventListener
    void onCommitted(ImportCommitted e) {
        if (!"att".equals(e.ownerModule()) || !CODE.equals(e.importType())) {
            return;
        }
        Runnable publish = () -> {
            try {
                service.jobCommitted(e.jobId());
                service.publish(e.jobId());
            } catch (RuntimeException ex) {
                log.warn("Attendance result import {} committed but not published (rows stay STAGED; publish again"
                        + " with POST /result-imports/{}/publish): {}", e.jobId(), e.jobId(), ex.getMessage());
            }
        };
        if (TenantContext.isBound() && TenantContext.current().tenantId().equals(e.tenantId())) {
            publish.run();
        } else {
            TenantContext.runWith(new TenantContext(e.tenantId(), null, "att-result-import", null, null), publish);
        }
    }

    private Row parse(ImportContext ctx, ImportRow row, Map<String, Object> st, List<RowMessage> out) {
        String no = row.text("employee_no");
        UUID emp = no == null ? null : s.employmentByNumber(ctx, st, no);
        if (emp == null) {
            out.add(RowMessage.error("employee_no", "employee-not-found", "Không tìm thấy nhân viên " + no
                    + " của pháp nhân trong phạm vi dữ liệu", "Employee " + no
                    + " is not an employee of the job's legal entity in your scope"));
            return null;
        }
        LocalDate start = row.date("period_start");
        BigDecimal qty = row.decimal("quantity");
        return new Row(ctx.jobId(), row.rowNo(), ctx.legalEntityId(), emp, start, row.text("category"),
                row.text("unit"), qty, row.decimal("period_quantity"));
    }
}
