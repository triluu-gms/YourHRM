package vn.yourhrm.attendance.internal.application.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.report.EmployeeNames.Name;
import vn.yourhrm.attendance.internal.domain.report.ReportFigures;
import vn.yourhrm.attendance.internal.domain.report.TimesheetGrid;
import vn.yourhrm.attendance.internal.domain.report.TimesheetGrid.Day;
import vn.yourhrm.attendance.internal.domain.report.TimesheetGrid.Row;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore;
import vn.yourhrm.attendance.internal.infra.report.ReportStore;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.ExceptionRow;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.PunchLogRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CodeRow;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Timesheet reports of BRD 03 §8: R-01 the monthly timesheet grid (bảng chấm công) with its Excel export in the VN
 * layout, R-02 the daily exceptions and R-03 the raw punch log. Rows are limited to the caller's data scope of
 * {@code att.timesheet.view} (R-01, R-02) or {@code att.punch.view} (R-03), like the {@code /timesheets} grid.
 */
@Service
public class TimesheetReports {

    static final int MAX_RANGE_DAYS = 62;
    /** Largest grid exported in one file (employments). */
    static final int MAX_EXPORT_ROWS = 5000;
    private static final Set<String> EXCEPTION_TYPES = Set.of("LATE", "EARLY", "MISSING_PUNCH", "ABSENT");
    private static final Set<String> PUNCH_STATUSES = Set.of("PENDING", "VALID", "QUARANTINED", "QUARANTINED_UNMAPPED",
            "QUARANTINED_EXPIRED", "QUARANTINED_UNKNOWN_DEVICE", "IGNORED");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    public record GridQuery(UUID legalEntityId, UUID orgUnitId, UUID periodId, LocalDate from, LocalDate to,
            String lang) {
    }

    public record GridDate(LocalDate date, int dayOfMonth, String weekday) {
    }

    public record Column(String code, String label) {
    }

    public record CodeLegend(String code, String label, String payrollCategory) {
    }

    public record GridRow(UUID employmentId, String employeeNo, String fullName, UUID orgUnitId, List<String> cells,
            Map<String, BigDecimal> codeTotals, Map<String, BigDecimal> categoryTotals, BigDecimal workedHours,
            BigDecimal overtimeHours, BigDecimal nightHours, int lateMin, int earlyMin, int exceptionDays,
            int missingDays) {
    }

    /** R-01: the grid page, its columns and the totals of the page's rows. */
    public record GridReport(UUID legalEntityId, UUID orgUnitId, UUID periodId, LocalDate from, LocalDate to,
            List<GridDate> dates, List<Column> categories, List<CodeLegend> codes, ReportPage<GridRow> page,
            Map<String, BigDecimal> pageTotals) {
    }

    public record ExceptionLine(UUID employmentId, LocalDate workDate, UUID legalEntityId, UUID orgUnitId,
            String dayStatus, String dayStatusLabel, String primaryCode, java.time.Instant firstInAt,
            java.time.Instant lastOutAt, BigDecimal scheduledHours, BigDecimal workedHours, int lateMin,
            int earlyMin, BigDecimal absentHours, List<Column> exceptions, boolean blocking) {
    }

    /** R-02: counts per exception type over the whole filter, and the page of days. */
    public record ExceptionReport(LocalDate from, LocalDate to, List<Count> summary, ReportPage<ExceptionLine> page) {
    }

    public record Count(String code, String label, long count) {
    }

    public record PunchLine(PunchLogRow punch, String validationStatusLabel, String sourceLabel) {
    }

    private final ReportStore store;
    private final TimesheetStore timesheets;
    private final AttendancePeriodStore periods;
    private final EmployeeNames names;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final BusinessClock clock;

    public TimesheetReports(ReportStore store, TimesheetStore timesheets, AttendancePeriodStore periods,
            EmployeeNames names, AuthorizationService authz, AuditLog audit, BusinessClock clock) {
        this.store = store;
        this.timesheets = timesheets;
        this.periods = periods;
        this.names = names;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- R-01 grid

    @AttTransactional(readOnly = true)
    public GridReport grid(GridQuery q, Integer limit, Integer offset) {
        GridQuery r = resolve(q);
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        ScopePredicate scope = authz.scopeFor(AttendancePermissions.TIMESHEET_VIEW, clock.today());
        Map<String, CodeRow> codes = timesheets.effectiveCodes();
        List<UUID> ids = store.gridEmployments(r.legalEntityId(), r.orgUnitId(), r.from(), r.to(), scope, lim + 1,
                off);
        ReportPage<UUID> page = ReportPage.of(ids, lim, off);
        List<GridRow> rows = rows(page.items(), r, scope, codes);
        return report(r, codes, new ReportPage<>(rows, lim, off, page.hasMore()));
    }

    /** R-01 Excel: every row of the filter (up to {@value #MAX_EXPORT_ROWS}), VN layout with the signature cells. */
    @AttTransactional(readOnly = true)
    public ReportExport gridExcel(GridQuery q, String unitLabel) {
        GridQuery r = resolve(q);
        String lang = ReportLabels.lang(r.lang());
        ScopePredicate scope = authz.scopeFor(AttendancePermissions.TIMESHEET_VIEW, clock.today());
        Map<String, CodeRow> codes = timesheets.effectiveCodes();
        List<GridRow> rows = new ArrayList<>();
        int chunk = 500;
        for (int off = 0; ; off += chunk) {
            List<UUID> ids = store.gridEmployments(r.legalEntityId(), r.orgUnitId(), r.from(), r.to(), scope, chunk,
                    off);
            rows.addAll(rows(ids, r, scope, codes));
            if (ids.size() < chunk) {
                break;
            }
            if (rows.size() >= MAX_EXPORT_ROWS) {
                throw ApiException.unprocessable("export-too-large", "The grid has more than " + MAX_EXPORT_ROWS
                        + " employees: export it per legal entity or org unit");
            }
        }
        GridReport g = report(r, codes, new ReportPage<>(rows, rows.size(), 0, false));
        byte[] body = ReportWorkbook.write(gridSheet(g, lang, unitLabel));
        audit.record(new BusinessAction("EXPORT", "att", "timesheet_grid", null, r.legalEntityId(),
                Map.of("from", r.from().toString(), "to", r.to().toString(), "rows", String.valueOf(rows.size()))));
        return new ReportExport("bang-cham-cong_" + r.from() + "_" + r.to() + ".xlsx", ReportWorkbook.MEDIA_TYPE, body);
    }

    private GridQuery resolve(GridQuery q) {
        UUID le = q.legalEntityId();
        LocalDate from = q.from();
        LocalDate to = q.to();
        if (q.periodId() != null) {
            var p = periods.find(q.periodId()).orElseThrow(() -> ApiException.notFound("Attendance period",
                    q.periodId()));
            if (le != null && !le.equals(p.legalEntityId())) {
                throw ApiException.unprocessable("period-other-legal-entity",
                        "The period belongs to another legal entity");
            }
            le = p.legalEntityId();
            from = from == null ? p.periodStart() : from;
            to = to == null ? p.periodEnd() : to;
        }
        ReportSupport.range(from, to, MAX_RANGE_DAYS);
        return new GridQuery(le, q.orgUnitId(), q.periodId(), from, to, q.lang());
    }

    private List<GridRow> rows(List<UUID> ids, GridQuery r, ScopePredicate scope, Map<String, CodeRow> codes) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Day> days = store.gridDays(ids, r.legalEntityId(), r.orgUnitId(), r.from(), r.to(), scope);
        Map<String, String> categoryOf = new LinkedHashMap<>();
        codes.forEach((c, row) -> categoryOf.put(c, row.payrollCategory()));
        List<Row> rows = TimesheetGrid.assemble(ids, days, r.from(), r.to(), categoryOf);
        Map<UUID, Long> ot = store.paidOvertimeMinutes(ids, r.from(), r.to());
        Map<UUID, Name> who = names.of(ids, r.to());
        return rows.stream().map(x -> {
            Name n = who.getOrDefault(x.employmentId(), Name.NONE);
            return new GridRow(x.employmentId(), n.employeeNo(), n.fullName(), x.orgUnitId(), x.cells(),
                    x.codeTotals(), x.categoryTotals(), x.workedHours(),
                    ReportFigures.hours(ot.getOrDefault(x.employmentId(), 0L)), x.nightHours(), x.lateMin(),
                    x.earlyMin(), x.exceptionDays(), x.missingDays());
        }).toList();
    }

    private GridReport report(GridQuery r, Map<String, CodeRow> codes, ReportPage<GridRow> page) {
        String lang = ReportLabels.lang(r.lang());
        List<GridDate> dates = TimesheetGrid.dates(r.from(), r.to()).stream()
                .map(d -> new GridDate(d, d.getDayOfMonth(), ReportLabels.weekday(d.getDayOfWeek(), lang))).toList();
        List<Row> asRows = page.items().stream().map(g -> new Row(g.employmentId(), g.orgUnitId(), g.cells(),
                g.codeTotals(), g.categoryTotals(), g.workedHours(), g.lateMin(), g.earlyMin(), g.nightHours(),
                g.exceptionDays(), g.missingDays())).toList();
        List<Column> categories = TimesheetGrid.categories(asRows).stream()
                .map(c -> new Column(c, ReportLabels.text(c, lang))).toList();
        Set<String> used = new LinkedHashSet<>();
        page.items().forEach(g -> used.addAll(g.codeTotals().keySet()));
        List<CodeLegend> legend = codes.values().stream().filter(c -> used.contains(c.code()))
                .map(c -> new CodeLegend(c.code(), ReportLabels.pick(c.labelVi(), c.labelEn(), lang),
                        c.payrollCategory()))
                .toList();
        return new GridReport(r.legalEntityId(), r.orgUnitId(), r.periodId(), r.from(), r.to(), dates, categories,
                legend, page, TimesheetGrid.grandTotals(asRows));
    }

    private static ReportWorkbook.Sheet gridSheet(GridReport g, String lang, String unitLabel) {
        boolean wholeMonth = g.from().getDayOfMonth() == 1 && g.to().equals(YearMonth.from(g.from()).atEndOfMonth());
        String sub = wholeMonth ? ReportLabels.text("month", lang) + " " + MONTH.format(g.from())
                : ReportLabels.text("from", lang) + " " + ReportWorkbook.date(g.from()) + " "
                        + ReportLabels.text("to", lang).toLowerCase(java.util.Locale.ROOT) + " "
                        + ReportWorkbook.date(g.to());
        String unit = ReportLabels.text("unit", lang) + ": "
                + (unitLabel == null || unitLabel.isBlank() ? "...................................." : unitLabel);
        List<String> h1 = new ArrayList<>(List.of(ReportLabels.text("no", lang), ReportLabels.text("employeeNo", lang),
                ReportLabels.text("fullName", lang)));
        List<String> h2 = new ArrayList<>(List.of("", "", ""));
        for (GridDate d : g.dates()) {
            h1.add(String.valueOf(d.dayOfMonth()));
            h2.add(d.weekday());
        }
        for (Column c : g.categories()) {
            h1.add(c.label());
            h2.add("");
        }
        for (String k : List.of("workedHours", "otHours", "nightHours")) {
            h1.add(ReportLabels.text(k, lang));
            h2.add("");
        }
        List<List<Object>> rows = new ArrayList<>();
        int no = 1;
        for (GridRow r : g.page().items()) {
            List<Object> v = new ArrayList<>();
            v.add(no++);
            v.add(r.employeeNo() == null ? "" : r.employeeNo());
            v.add(r.fullName() == null ? r.employmentId().toString() : r.fullName());
            v.addAll(r.cells());
            for (Column c : g.categories()) {
                v.add(r.categoryTotals().getOrDefault(c.code(), BigDecimal.ZERO));
            }
            v.add(r.workedHours());
            v.add(r.overtimeHours());
            v.add(r.nightHours());
            rows.add(v);
        }
        List<Object> total = new ArrayList<>();
        total.add(ReportLabels.text("total", lang));
        total.add("");
        total.add("");
        g.dates().forEach(d -> total.add(""));
        for (Column c : g.categories()) {
            total.add(g.pageTotals().getOrDefault(c.code(), BigDecimal.ZERO));
        }
        total.add(g.page().items().stream().map(GridRow::workedHours).reduce(BigDecimal.ZERO, BigDecimal::add));
        total.add(g.page().items().stream().map(GridRow::overtimeHours).reduce(BigDecimal.ZERO, BigDecimal::add));
        total.add(g.page().items().stream().map(GridRow::nightHours).reduce(BigDecimal.ZERO, BigDecimal::add));
        List<String> footer = new ArrayList<>();
        if (!g.codes().isEmpty()) {
            footer.add(ReportLabels.text("legend", lang) + ": " + String.join("; ",
                    g.codes().stream().map(c -> c.code() + " = " + c.label()).toList()));
        }
        return new ReportWorkbook.Sheet(ReportLabels.text("R01", lang), List.of(ReportLabels.text("R01", lang), sub,
                unit), List.of(h1, h2), rows, total, footer, signatures(lang), hints(lang),
                ReportLabels.text("dateLine", lang), 3, g.dates().size());
    }

    static List<String> signatures(String lang) {
        return List.of(ReportLabels.text("preparedBy", lang), ReportLabels.text("headOfUnit", lang),
                ReportLabels.text("director", lang));
    }

    static List<String> hints(String lang) {
        return List.of(ReportLabels.text("signHint", lang), ReportLabels.text("signHint", lang),
                ReportLabels.text("directorHint", lang));
    }

    // ------------------------------------------------------------------------------------- R-02 exceptions

    @AttTransactional(readOnly = true)
    public ExceptionReport exceptions(UUID legalEntityId, UUID orgUnitId, UUID employmentId, LocalDate from,
            LocalDate to, String type, String lang, Integer limit, Integer offset) {
        ReportSupport.range(from, to, MAX_RANGE_DAYS);
        String ty = ReportSupport.oneOf(type, "type", EXCEPTION_TYPES);
        String l = ReportLabels.lang(lang);
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        ScopePredicate scope = authz.scopeFor(AttendancePermissions.TIMESHEET_VIEW, clock.today());
        List<ExceptionRow> rows = store.exceptions(legalEntityId, orgUnitId, employmentId, from, to, ty, scope,
                lim + 1, off);
        List<Count> summary = store.exceptionCounts(legalEntityId, orgUnitId, employmentId, from, to, scope)
                .entrySet().stream().map(e -> new Count(e.getKey(), ReportLabels.text(e.getKey(), l), e.getValue()))
                .toList();
        ReportPage<ExceptionLine> page = ReportPage.of(rows, lim, off).map(x -> new ExceptionLine(x.employmentId(),
                x.workDate(), x.legalEntityId(), x.orgUnitId(), x.dayStatus(), ReportLabels.dayStatus(x.dayStatus(), l),
                x.primaryCode(), x.firstInAt(), x.lastOutAt(), x.scheduledHours(), x.workedHours(), x.lateMin(),
                x.earlyMin(), x.absentHours(),
                x.exceptions().stream().map(c -> new Column(c, ReportLabels.exception(c, l))).toList(),
                x.blocking()));
        return new ExceptionReport(from, to, summary, page);
    }

    // ------------------------------------------------------------------------------------ R-03 punch log

    /** Raw punches with source and validation status; every read is audited (NFR-ATT-008). */
    @AttTransactional(readOnly = true)
    public ReportPage<PunchLine> punches(UUID legalEntityId, UUID orgUnitId, UUID deviceId, UUID employmentId,
            String status, LocalDate from, LocalDate to, String lang, Integer limit, Integer offset) {
        ReportSupport.range(from, to, MAX_RANGE_DAYS);
        String st = ReportSupport.oneOf(status, "status", PUNCH_STATUSES);
        String l = ReportLabels.lang(lang);
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        List<PunchLogRow> rows = store.punches(legalEntityId, orgUnitId, deviceId, employmentId, st, from, to,
                authz.scopeFor(AttendancePermissions.PUNCH_VIEW, clock.today()), lim + 1, off);
        ReportPage<PunchLine> page = ReportPage.of(rows, lim, off).map(p -> new PunchLine(p,
                ReportLabels.text(p.validationStatus(), l), ReportLabels.text(p.source(), l)));
        Map<String, String> details = new LinkedHashMap<>();
        details.put("report", "R-03");
        details.put("from", from.toString());
        details.put("to", to.toString());
        details.put("rows", String.valueOf(page.items().size()));
        if (employmentId != null) {
            details.put("employmentId", employmentId.toString());
        }
        audit.record(new BusinessAction("VIEW", "att", "raw_punch", employmentId, legalEntityId, details));
        return page;
    }
}
