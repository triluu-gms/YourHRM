package vn.yourhrm.attendance.internal.application.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.report.EmployeeNames.Name;
import vn.yourhrm.attendance.internal.domain.report.ReportFigures;
import vn.yourhrm.attendance.internal.domain.report.ReportFigures.CapUsage;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.report.ReportStore;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.OtLedger;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.OverrideRow;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.Placed;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Overtime reports of BRD 03 §8: R-04 the overtime summary per employee (month, year to date, usage of the monthly
 * and annual caps of {@code att.ot_cap_ledger}, paid hours per legal category of {@code att.overtime_result}) with its
 * Excel export, and R-05 the log of overtime approved above the cap (authorised overrides, BR-ATT-046). The caps are
 * the ledger's snapshots of the legal pack (40 h / month, 200 h or 300 h / year per the regime): nothing is
 * hard-coded here. Data scope of {@code att.overtime.view}.
 */
@Service
public class OvertimeReports {

    static final int MAX_RANGE_DAYS = 366;
    static final int MAX_EXPORT_ROWS = 5000;
    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    public record OvertimeRow(UUID employmentId, String employeeNo, String fullName, UUID legalEntityId,
            UUID orgUnitId, CapUsage month, CapUsage year, String regimeLabel, String regimeNoticeRef,
            Map<String, BigDecimal> monthByCategory, Map<String, BigDecimal> yearToDateByCategory) {
    }

    public record CategoryColumn(String code, String label) {
    }

    public record OvertimeSummary(YearMonth month, UUID legalEntityId, UUID orgUnitId, List<CategoryColumn> categories,
            ReportPage<OvertimeRow> page) {
    }

    public record OverrideLine(OverrideRow request, String statusLabel, CapUsage month, CapUsage year) {
    }

    private final ReportStore store;
    private final EmployeeNames names;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final BusinessClock clock;

    public OvertimeReports(ReportStore store, EmployeeNames names, AuthorizationService authz, AuditLog audit,
            BusinessClock clock) {
        this.store = store;
        this.names = names;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- R-04 summary

    @AttTransactional(readOnly = true)
    public OvertimeSummary summary(UUID legalEntityId, UUID orgUnitId, YearMonth month, String lang, Integer limit,
            Integer offset) {
        YearMonth m = require(month);
        String l = ReportLabels.lang(lang);
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        ScopePredicate scope = authz.scopeFor(AttendancePermissions.OVERTIME_VIEW, clock.today());
        List<Placed> placed = store.overtimeEmployments(legalEntityId, orgUnitId, m.atDay(1),
                LocalDate.of(m.getYear(), 1, 1), m.atEndOfMonth(), scope, lim + 1, off);
        ReportPage<Placed> page = ReportPage.of(placed, lim, off);
        return new OvertimeSummary(m, legalEntityId, orgUnitId, categories(l),
                new ReportPage<>(rows(page.items(), m, l), lim, off, page.hasMore()));
    }

    @AttTransactional(readOnly = true)
    public ReportExport summaryExcel(UUID legalEntityId, UUID orgUnitId, YearMonth month, String lang, String unitLabel) {
        YearMonth m = require(month);
        String l = ReportLabels.lang(lang);
        ScopePredicate scope = authz.scopeFor(AttendancePermissions.OVERTIME_VIEW, clock.today());
        List<OvertimeRow> rows = new ArrayList<>();
        int chunk = 500;
        for (int off = 0; ; off += chunk) {
            List<Placed> placed = store.overtimeEmployments(legalEntityId, orgUnitId, m.atDay(1),
                    LocalDate.of(m.getYear(), 1, 1), m.atEndOfMonth(), scope, chunk, off);
            rows.addAll(rows(placed, m, l));
            if (placed.size() < chunk) {
                break;
            }
            if (rows.size() >= MAX_EXPORT_ROWS) {
                throw ApiException.unprocessable("export-too-large", "More than " + MAX_EXPORT_ROWS
                        + " employees: export per legal entity or org unit");
            }
        }
        List<CategoryColumn> cats = categories(l);
        List<String> h = new ArrayList<>(List.of(ReportLabels.text("no", l), ReportLabels.text("employeeNo", l),
                ReportLabels.text("fullName", l), ReportLabels.text("monthUsed", l), ReportLabels.text("monthCap", l),
                ReportLabels.text("monthPct", l), ReportLabels.text("yearUsed", l), ReportLabels.text("yearCap", l),
                ReportLabels.text("yearPct", l), ReportLabels.text("regime", l)));
        cats.forEach(c -> h.add(c.label()));
        List<List<Object>> data = new ArrayList<>();
        int no = 1;
        for (OvertimeRow r : rows) {
            List<Object> v = new ArrayList<>();
            v.add(no++);
            v.add(r.employeeNo() == null ? "" : r.employeeNo());
            v.add(r.fullName() == null ? r.employmentId().toString() : r.fullName());
            v.add(r.month() == null ? null : r.month().usedHours());
            v.add(r.month() == null ? null : r.month().capHours());
            v.add(r.month() == null ? null : r.month().usedPercent());
            v.add(r.year() == null ? null : r.year().usedHours());
            v.add(r.year() == null ? null : r.year().capHours());
            v.add(r.year() == null ? null : r.year().usedPercent());
            v.add(r.regimeLabel() == null ? "" : r.regimeLabel());
            cats.forEach(c -> v.add(r.monthByCategory().getOrDefault(c.code(), BigDecimal.ZERO)));
            data.add(v);
        }
        String unit = ReportLabels.text("unit", l) + ": "
                + (unitLabel == null || unitLabel.isBlank() ? "...................................." : unitLabel);
        byte[] body = ReportWorkbook.write(new ReportWorkbook.Sheet(ReportLabels.text("R04", l),
                List.of(ReportLabels.text("R04", l), ReportLabels.text("month", l) + " " + MONTH.format(m), unit),
                List.of(h), data, null, List.of(), TimesheetReports.signatures(l), TimesheetReports.hints(l),
                ReportLabels.text("dateLine", l), 0, 0));
        audit.record(new BusinessAction("EXPORT", "att", "overtime_summary", null, legalEntityId,
                Map.of("month", m.toString(), "rows", String.valueOf(rows.size()))));
        return new ReportExport("tong-hop-lam-them-gio_" + m + ".xlsx", ReportWorkbook.MEDIA_TYPE, body);
    }

    private List<OvertimeRow> rows(List<Placed> placed, YearMonth m, String lang) {
        if (placed.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = placed.stream().map(Placed::employmentId).toList();
        LocalDate ms = m.atDay(1);
        LocalDate ys = LocalDate.of(m.getYear(), 1, 1);
        Map<UUID, OtLedger> monthRows = new LinkedHashMap<>();
        Map<UUID, OtLedger> yearRows = new LinkedHashMap<>();
        for (OtLedger x : store.overtimeLedger(ids, ms, ys)) {
            ("MONTH".equals(x.periodKind()) ? monthRows : yearRows).put(x.employmentId(), x);
        }
        Map<UUID, Map<String, Long>> monthCats = store.overtimeByCategory(ids, ms, m.atEndOfMonth());
        Map<UUID, Map<String, Long>> ytdCats = store.overtimeByCategory(ids, ys, m.atEndOfMonth());
        Map<UUID, Name> who = names.of(ids, m.atEndOfMonth());
        List<OvertimeRow> out = new ArrayList<>();
        for (Placed p : placed) {
            OtLedger y = yearRows.get(p.employmentId());
            Name n = who.getOrDefault(p.employmentId(), Name.NONE);
            out.add(new OvertimeRow(p.employmentId(), n.employeeNo(), n.fullName(), p.legalEntityId(), p.orgUnitId(),
                    usage(monthRows.get(p.employmentId())), usage(y),
                    y == null ? null : ReportLabels.text(y.regimeCode(), lang), y == null ? null : y.regimeNoticeRef(),
                    ReportFigures.otByCategory(monthCats.getOrDefault(p.employmentId(), Map.of())),
                    ReportFigures.otByCategory(ytdCats.getOrDefault(p.employmentId(), Map.of()))));
        }
        return out;
    }

    private static CapUsage usage(OtLedger x) {
        return x == null ? null : ReportFigures.capUsage(x.regimeCode(), x.capMin(), x.openingMin(), x.approvedMin(),
                x.pendingMin(), x.paidMin(), x.overrideMin());
    }

    private static List<CategoryColumn> categories(String lang) {
        return ReportFigures.OT_CATEGORIES.stream().map(c -> new CategoryColumn(c, ReportLabels.text(c, lang)))
                .toList();
    }

    private static YearMonth require(YearMonth month) {
        if (month == null) {
            throw ApiException.unprocessable("month-required", "Give the month (YYYY-MM)");
        }
        return month;
    }

    // ------------------------------------------------------------------------------------ R-05 cap overrides

    @AttTransactional(readOnly = true)
    public ReportPage<OverrideLine> capOverrides(UUID legalEntityId, UUID orgUnitId, UUID employmentId,
            LocalDate from, LocalDate to, String status, String lang, Integer limit, Integer offset) {
        ReportSupport.range(from, to, MAX_RANGE_DAYS);
        String st = ReportSupport.oneOf(status, "status", STATUSES);
        String l = ReportLabels.lang(lang);
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        List<OverrideRow> rows = store.capOverrides(legalEntityId, orgUnitId, employmentId, from, to, st,
                authz.scopeFor(AttendancePermissions.OVERTIME_VIEW, clock.today()), lim + 1, off);
        return ReportPage.of(rows, lim, off).map(r -> new OverrideLine(r, ReportLabels.status(r.status(), l),
                usage(r.month()), usage(r.year())));
    }
}
