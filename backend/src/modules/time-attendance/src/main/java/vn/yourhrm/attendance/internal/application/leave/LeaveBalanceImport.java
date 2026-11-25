package vn.yourhrm.attendance.internal.application.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.punch.PunchImportSupport;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.LedgerRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.NewTxn;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeRow;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Opening leave balances at go-live ({@code OB_LEAVE_BALANCE}, ADR-0037, BR-ATT-068; DESIGN §13): one row per
 * employee × leave type × leave year with the entitlement, days taken, days carried in and the closing balance. The
 * closing balance becomes one OPENING ledger row dated the go-live date ({@code source = OPENING_LOAD}); the other
 * figures are kept in the row's reason for reconciliation; carried days with an expiry date are a separate CARRY row
 * so the carry-expiry run expires them. Opening-balance mode only. The ledger is append-only, so a
 * committed opening cannot be undone or changed: a re-run with the same value is a no-op, a different value is an
 * error (use a balance adjustment). Reconciliation: Σ closing balance per leave type (check {@code LEAVE_BALANCE}).
 */
@Component
class LeaveBalanceImport implements ImportType {

    static final String CODE = "OB_LEAVE_BALANCE";
    static final String CHECK = "LEAVE_BALANCE";

    private final PunchImportSupport s;
    private final LeaveStore store;
    private final PunchStore punches;
    private final AuthorizationService authz;

    LeaveBalanceImport(PunchImportSupport support, LeaveStore store, PunchStore punches, AuthorizationService authz) {
        this.s = support;
        this.store = store;
        this.punches = punches;
        this.authz = authz;
    }

    @Override
    public Definition definition() {
        return new Definition(CODE, "att", "Số dư phép đầu kỳ", "Opening leave balances", "1",
                AttendancePermissions.LEAVE_BALANCE_IMPORT, List.of(
                        Column.of("employee_no", "Mã nhân viên", "Employee number", ColumnType.TEXT, true),
                        Column.of("leave_type", "Loại nghỉ", "Leave type", ColumnType.TEXT, true),
                        Column.of("leave_year", "Năm phép", "Leave year", ColumnType.INTEGER, true),
                        Column.of("entitled_days", "Số ngày được hưởng", "Entitled days", ColumnType.DECIMAL, false),
                        Column.of("taken_days", "Số ngày đã nghỉ", "Days taken", ColumnType.DECIMAL, false),
                        Column.of("carried_days", "Số ngày chuyển sang", "Days carried in", ColumnType.DECIMAL, false),
                        Column.of("closing_balance", "Số dư cuối", "Closing balance", ColumnType.DECIMAL, true),
                        Column.of("carry_expires_on", "Ngày hết hạn phép chuyển", "Carried days expire on",
                                ColumnType.DATE, false)),
                List.of("employee_no", "leave_type", "leave_year"), false, true, List.of(),
                List.of(new ControlCheck(CHECK, "Tổng số dư phép theo loại nghỉ", "Total balance per leave type")));
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
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (ImportRow r : rows) {
            BigDecimal v = r.decimal("closing_balance");
            String code = r.text("leave_type");
            if (v != null && code != null) {
                sums.merge(LeaveInputs.upper(code), v, BigDecimal::add);
            }
        }
        return sums.entrySet().stream().map(e -> new ReconciliationValue(CHECK, e.getKey(), e.getValue())).toList();
    }

    @Override
    public List<ReconciliationValue> committedTotals(ImportContext ctx, List<CommittedRow> rows) {
        return s.write(() -> store.openingTotals(ctx.jobId())).entrySet().stream()
                .map(e -> new ReconciliationValue(CHECK, e.getKey(), e.getValue())).toList();
    }

    // ------------------------------------------------------------------------------------------------ rows

    private record Parsed(UUID employmentId, String typeCode, int year, BigDecimal entitled, BigDecimal taken,
            BigDecimal carried, BigDecimal closing, LocalDate expires) {
    }

    private Parsed parse(ImportContext ctx, ImportRow row, Map<String, Object> st, List<RowMessage> out) {
        if (!ctx.openingBalance()) {
            out.add(RowMessage.error(null, "opening-balance-mode-required",
                    "Số dư phép đầu kỳ chỉ được nạp ở chế độ số dư đầu kỳ (go-live)",
                    "Opening leave balances are loaded in opening-balance mode (go-live) only"));
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
        Object y = row.values().get("leave_year");
        int year;
        try {
            year = y == null ? 0 : new BigDecimal(y.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            year = 0;
        }
        if (year < 2000 || year > 2100) {
            out.add(RowMessage.error("leave_year", "invalid-leave-year", "Năm phép không hợp lệ",
                    "Invalid leave year"));
            return null;
        }
        BigDecimal closing = row.decimal("closing_balance");
        BigDecimal entitled = row.decimal("entitled_days");
        BigDecimal taken = row.decimal("taken_days");
        BigDecimal carried = row.decimal("carried_days");
        if (entitled != null && taken != null && closing != null) {
            BigDecimal expect = entitled.add(carried == null ? BigDecimal.ZERO : carried).subtract(taken);
            if (expect.compareTo(closing) != 0) {
                out.add(RowMessage.error("closing_balance", "balance-mismatch",
                        "Số dư cuối phải bằng được hưởng + chuyển sang − đã nghỉ (" + expect.toPlainString() + ")",
                        "The closing balance must equal entitled + carried − taken (" + expect.toPlainString() + ")"));
                return null;
            }
        }
        LocalDate expires = row.date("carry_expires_on");
        return new Parsed(emp, LeaveInputs.upper(row.text("leave_type")), year, entitled, taken, carried, closing,
                expires);
    }

    private CommitResult write(ImportContext ctx, Parsed p, boolean dryRun, List<RowMessage> warnings) {
        TypeRow t = store.typeByCode(p.typeCode()).filter(x -> x.data().tracksBalance())
                .orElseThrow(() -> ApiException.unprocessable("unknown-leave-type", "Leave type " + p.typeCode()
                        + " does not exist or does not track a balance"));
        LocalDate goLive = s.asOf(ctx);
        PunchStore.Placement where = punches.placement(p.employmentId(), goLive)
                .orElseThrow(() -> ApiException.unprocessable("employment-not-in-attendance",
                        "The employee is not known to Time & Attendance yet (load the employee master first)"));
        if (!authz.can(AttendancePermissions.LEAVE_BALANCE_IMPORT, new AuthorizationTarget.Employee(p.employmentId(),
                where.legalEntityId(), where.orgUnitId(), where.locationId()), goLive)) {
            throw ApiException.unprocessable("employment-not-in-scope", "The employee is outside your data scope");
        }
        // carried days with an expiry date become their own CARRY row, so the carry-expiry run expires them
        boolean split = p.carried() != null && p.carried().signum() > 0 && p.expires() != null;
        BigDecimal opening = split ? p.closing().subtract(p.carried()) : p.closing();
        var existing = store.opening(p.employmentId(), t.id(), p.year());
        if (existing.isPresent()) {
            LedgerRow o = existing.get();
            if (o.quantity().compareTo(opening) != 0) {
                throw ApiException.unprocessable("opening-exists", "An opening balance of " + o.quantity().toPlainString()
                        + " days was loaded before; correct it with a balance adjustment");
            }
            if (dryRun) {
                warnings.add(RowMessage.warning(null, "already-loaded", "Số dư đầu kỳ đã được nạp; bỏ qua",
                        "The opening balance was loaded before; skipped"));
            }
            return CommitResult.updated("att.leave_balance_ledger", o.id());
        }
        String reason = "entitled=" + plain(p.entitled()) + "; taken=" + plain(p.taken()) + "; carried="
                + plain(p.carried());
        store.insertTxn(new NewTxn(p.employmentId(), where.legalEntityId(), where.orgUnitId(), t.id(),
                t.data().code(), p.year(), "OPENING", opening, goLive, null, null, null, "IMPORT", ctx.jobId(),
                reason, ctx.jobId(), "OPENING_LOAD"));
        if (split) {
            store.insertTxn(new NewTxn(p.employmentId(), where.legalEntityId(), where.orgUnitId(), t.id(),
                    t.data().code(), p.year(), "CARRY", p.carried(), goLive, null, p.expires(), null, "IMPORT",
                    ctx.jobId(), "Carried days of the opening balance", ctx.jobId(), "OPENING_LOAD"));
        }
        return CommitResult.created("att.leave_balance_ledger",
                store.opening(p.employmentId(), t.id(), p.year()).orElseThrow().id());
    }

    private static String plain(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }
}
