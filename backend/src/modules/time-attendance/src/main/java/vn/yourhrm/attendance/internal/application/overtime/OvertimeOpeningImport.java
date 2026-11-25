package vn.yourhrm.attendance.internal.application.overtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.overtime.OvertimePolicy.Limits;
import vn.yourhrm.attendance.internal.application.punch.PunchImportSupport;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.EmploymentFacts;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.LedgerRow;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.Placement;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Year-to-date overtime at go-live ({@code OB_OT_YTD}, BR-ATT-086): one row per employee with the overtime hours
 * already worked in the go-live year before the go-live date, and the part of them worked in the go-live month. They
 * become {@code opening_min} of the employee's YEAR and MONTH rows of {@code att.ot_cap_ledger}, so the monthly and
 * annual caps (BR-ATT-046) count them. Caps are per legal entity in Phase 1: the hours are those worked for the job's
 * legal entity. Opening-balance mode only; the year must be the go-live year (earlier years never count for a cap).
 * A re-run with the same value is a no-op; a different value is an error (the cap ledger is not an adjustment
 * channel). Reconciliation: Σ year-to-date hours (check {@code OT_YTD}).
 *
 * <p>ADR-0037 lists only {@code OB_LEAVE_BALANCE} for T&amp;A; this template is a T&amp;A proposal to add to it.
 */
@Component
class OvertimeOpeningImport implements ImportType {

    static final String CODE = "OB_OT_YTD";
    static final String CHECK = "OT_YTD";
    private static final BigDecimal MAX_HOURS = new BigDecimal("2000");

    private final PunchImportSupport s;
    private final OvertimeStore store;
    private final OvertimePolicy policy;
    private final AuthorizationService authz;

    OvertimeOpeningImport(PunchImportSupport support, OvertimeStore store, OvertimePolicy policy,
            AuthorizationService authz) {
        this.s = support;
        this.store = store;
        this.policy = policy;
        this.authz = authz;
    }

    @Override
    public Definition definition() {
        return new Definition(CODE, "att", "Số giờ làm thêm lũy kế đầu kỳ", "Year-to-date overtime", "1",
                AttendancePermissions.OVERTIME_IMPORT, List.of(
                        Column.of("employee_no", "Mã nhân viên", "Employee number", ColumnType.TEXT, true),
                        Column.of("year", "Năm", "Year", ColumnType.INTEGER, true),
                        Column.of("ytd_ot_hours", "Số giờ làm thêm từ đầu năm", "Overtime hours year to date",
                                ColumnType.DECIMAL, true),
                        Column.of("month_ot_hours", "Số giờ làm thêm trong tháng go-live",
                                "Overtime hours in the go-live month", ColumnType.DECIMAL, false)),
                List.of("employee_no", "year"), false, true, List.of(),
                List.of(new ControlCheck(CHECK, "Tổng số giờ làm thêm từ đầu năm", "Total year-to-date overtime hours")));
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
            List<RowMessage> w = new ArrayList<>();
            write(ctx, p, true, w);
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
        return s.write(() -> write(ctx, p, false, new ArrayList<>()));
    }

    @Override
    public List<ReconciliationValue> stagedTotals(ImportContext ctx, List<ImportRow> rows) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ImportRow r : rows) {
            BigDecimal v = r.decimal("ytd_ot_hours");
            if (v != null) {
                sum = sum.add(v);
            }
        }
        return List.of(new ReconciliationValue(CHECK, "HOURS", sum));
    }

    @Override
    public List<ReconciliationValue> committedTotals(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> ids = rows.stream().map(CommittedRow::targetId).filter(java.util.Objects::nonNull).toList();
        long minutes = s.write(() -> store.openingMinutes(ids));
        return List.of(new ReconciliationValue(CHECK, "HOURS",
                BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)
                        .stripTrailingZeros()));
    }

    // ------------------------------------------------------------------------------------------------ rows

    private record Parsed(UUID employmentId, int year, int ytdMin, int monthMin) {
    }

    private Parsed parse(ImportContext ctx, ImportRow row, Map<String, Object> st, List<RowMessage> out) {
        if (!ctx.openingBalance()) {
            out.add(RowMessage.error(null, "opening-balance-mode-required",
                    "Số giờ làm thêm lũy kế chỉ được nạp ở chế độ số dư đầu kỳ (go-live)",
                    "Year-to-date overtime is loaded in opening-balance mode (go-live) only"));
            return null;
        }
        String no = row.text("employee_no");
        UUID emp = no == null ? null : s.employmentByNumber(ctx, st, no);
        if (emp == null) {
            out.add(RowMessage.error("employee_no", "employee-not-found", "Không tìm thấy nhân viên " + no
                    + " của pháp nhân trong phạm vi dữ liệu", "Employee " + no
                    + " is not an employee of the job's legal entity in your scope"));
            return null;
        }
        LocalDate goLive = s.asOf(ctx);
        Object y = row.values().get("year");
        int year;
        try {
            year = y == null ? 0 : new BigDecimal(y.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            year = 0;
        }
        if (year != goLive.getYear()) {
            out.add(RowMessage.error("year", "year-not-go-live", "Năm phải là năm go-live " + goLive.getYear()
                    + " (giờ làm thêm của các năm trước không tính vào giới hạn)", "The year must be the go-live year "
                    + goLive.getYear() + " (overtime of earlier years does not count for a cap)"));
            return null;
        }
        BigDecimal ytd = row.decimal("ytd_ot_hours");
        BigDecimal month = row.decimal("month_ot_hours");
        if (ytd == null || ytd.signum() < 0 || ytd.compareTo(MAX_HOURS) > 0
                || (month != null && (month.signum() < 0 || month.compareTo(ytd) > 0))) {
            out.add(RowMessage.error("ytd_ot_hours", "invalid-hours",
                    "Số giờ không hợp lệ: từ 0, và số giờ trong tháng không vượt số giờ từ đầu năm",
                    "Invalid hours: 0 or more, and the month's hours at most the year-to-date hours"));
            return null;
        }
        if (month != null && month.signum() > 0 && goLive.getDayOfMonth() == 1) {
            out.add(RowMessage.error("month_ot_hours", "month-before-go-live",
                    "Go-live là ngày đầu tháng: không có giờ làm thêm nào của tháng go-live trước ngày go-live",
                    "Go-live is the first of the month: no overtime of the go-live month precedes it"));
            return null;
        }
        return new Parsed(emp, year, minutes(ytd), month == null ? 0 : minutes(month));
    }

    private static int minutes(BigDecimal hours) {
        return hours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private CommitResult write(ImportContext ctx, Parsed p, boolean dryRun, List<RowMessage> warnings) {
        LocalDate goLive = s.asOf(ctx);
        Placement where = store.placement(p.employmentId(), goLive)
                .orElseThrow(() -> ApiException.unprocessable("employment-not-in-attendance",
                        "The employee is not known to Time & Attendance yet (load the employee master first)"));
        if (!authz.can(AttendancePermissions.OVERTIME_IMPORT, new AuthorizationTarget.Employee(p.employmentId(),
                where.legalEntityId(), where.orgUnitId(), where.locationId()), goLive)) {
            throw ApiException.unprocessable("employment-not-in-scope", "The employee is outside your data scope");
        }
        Limits limits = policy.limits(where.legalEntityId(), where.locationId(), goLive);
        UUID person = store.employment(p.employmentId()).map(EmploymentFacts::personId).orElse(null);
        LedgerRow year = store.lockLedger(p.employmentId(), where.legalEntityId(), person, "YEAR",
                LocalDate.of(p.year(), 1, 1), limits.regime(), limits.yearCapMin(), limits.packId(),
                limits.noticeRef());
        LedgerRow month = store.lockLedger(p.employmentId(), where.legalEntityId(), person, "MONTH",
                goLive.withDayOfMonth(1), limits.regime(), limits.monthCapMin(), limits.packId(), null);
        boolean same = year.openingMin() == p.ytdMin() && month.openingMin() == p.monthMin();
        if (!same && (year.openingMin() != 0 || month.openingMin() != 0)) {
            throw ApiException.unprocessable("opening-exists", "Year-to-date overtime of "
                    + year.openingMin() + " min was loaded before for this employee and year");
        }
        if (same && (p.ytdMin() > 0 || p.monthMin() > 0)) {
            if (dryRun) {
                warnings.add(RowMessage.warning(null, "already-loaded", "Số giờ làm thêm lũy kế đã được nạp; bỏ qua",
                        "The year-to-date overtime was loaded before; skipped"));
            }
            return CommitResult.updated("att.ot_cap_ledger", year.id());
        }
        if (p.ytdMin() + year.approvedMin() + year.pendingMin() > limits.yearCapMin()) {
            warnings.add(RowMessage.warning("ytd_ot_hours", "above-annual-cap",
                    "Số giờ làm thêm từ đầu năm vượt giới hạn năm: mọi đăng ký làm thêm mới sẽ bị chặn",
                    "The year-to-date overtime exceeds the annual cap: every new overtime request will be blocked"));
        }
        if (p.monthMin() + month.approvedMin() + month.pendingMin() > limits.monthCapMin()) {
            warnings.add(RowMessage.warning("month_ot_hours", "above-monthly-cap",
                    "Số giờ làm thêm trong tháng vượt giới hạn tháng",
                    "The overtime of the go-live month exceeds the monthly cap"));
        }
        store.setOpening(year.id(), p.ytdMin());
        store.setOpening(month.id(), p.monthMin());
        return CommitResult.created("att.ot_cap_ledger", year.id());
    }
}
