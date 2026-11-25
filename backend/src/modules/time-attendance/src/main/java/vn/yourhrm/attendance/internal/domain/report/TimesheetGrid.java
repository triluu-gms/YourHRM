package vn.yourhrm.attendance.internal.domain.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The monthly timesheet grid (bảng chấm công, BRD 03 §8 R-01): one row per employment, one cell per calendar day with
 * the attendance code(s) of the day, and the totals per code and per payroll category (BRD §7.3). Pure: the days come
 * from {@code att.daily_timesheet}, the code → category map from the effective attendance code catalogue.
 */
public final class TimesheetGrid {

    /** Category columns in the order of the statutory layout; other categories follow in order of appearance. */
    public static final List<String> CATEGORY_ORDER = List.of("WORKED_DAYS", "PAID_LEAVE_DAYS", "HOLIDAY_DAYS",
            "SI_LEAVE_DAYS", "UNPAID_DAYS", "NOT_EMPLOYED_DAYS");

    /** Category of a code that is not in the catalogue (deleted / never configured). */
    public static final String UNMAPPED = "UNMAPPED";

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private TimesheetGrid() {
    }

    public record CodeQty(String code, BigDecimal qty) {
    }

    /** One computed day of an employment. */
    public record Day(UUID employmentId, LocalDate date, UUID orgUnitId, String dayStatus, String primaryCode,
            List<CodeQty> codes, BigDecimal workedHours, int lateMin, int earlyMin, BigDecimal nightHours,
            List<String> exceptions) {
    }

    /**
     * One employment of the grid.
     *
     * @param cells          one per date of the range (empty string: no computed day)
     * @param codeTotals     Σ quantity per attendance code (days)
     * @param categoryTotals Σ quantity per payroll category (days)
     * @param exceptionDays  days with at least one exception
     * @param missingDays    dates of the range without a computed day
     */
    public record Row(UUID employmentId, UUID orgUnitId, List<String> cells, Map<String, BigDecimal> codeTotals,
            Map<String, BigDecimal> categoryTotals, BigDecimal workedHours, int lateMin, int earlyMin,
            BigDecimal nightHours, int exceptionDays, int missingDays) {
    }

    /** The calendar dates of {@code [from, to]}. */
    public static List<LocalDate> dates(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }

    /**
     * The symbol of a day: the code for a full day ({@code X}), {@code X/2} for a half day, {@code X(0.25)} for any
     * other fraction, and the codes joined by {@code /} when the day carries several ({@code X/P}: half worked, half
     * annual leave). Without codes, the primary code, else empty.
     */
    public static String cell(Day d) {
        List<CodeQty> codes = d.codes() == null ? List.of()
                : d.codes().stream().filter(c -> c.code() != null && c.qty() != null && c.qty().signum() > 0).toList();
        if (codes.isEmpty()) {
            return d.primaryCode() == null ? "" : d.primaryCode();
        }
        if (codes.size() > 1) {
            return String.join("/", codes.stream().map(CodeQty::code).toList());
        }
        CodeQty c = codes.getFirst();
        if (c.qty().compareTo(BigDecimal.ONE) >= 0) {
            return c.code();
        }
        if (c.qty().compareTo(HALF) == 0) {
            return c.code() + "/2";
        }
        return c.code() + "(" + c.qty().stripTrailingZeros().toPlainString() + ")";
    }

    /**
     * Assembles the rows of {@code employments} (in that order) from their days in {@code [from, to]}.
     *
     * @param categoryOf payroll category per attendance code
     */
    public static List<Row> assemble(List<UUID> employments, Collection<Day> days, LocalDate from, LocalDate to,
            Map<String, String> categoryOf) {
        List<LocalDate> dates = dates(from, to);
        Map<UUID, Map<LocalDate, Day>> byEmployment = new LinkedHashMap<>();
        for (Day d : days) {
            if (!d.date().isBefore(from) && !d.date().isAfter(to)) {
                byEmployment.computeIfAbsent(d.employmentId(), k -> new LinkedHashMap<>()).put(d.date(), d);
            }
        }
        List<Row> rows = new ArrayList<>();
        for (UUID e : employments) {
            Map<LocalDate, Day> mine = byEmployment.getOrDefault(e, Map.of());
            List<String> cells = new ArrayList<>(dates.size());
            Map<String, BigDecimal> codeTotals = new LinkedHashMap<>();
            Map<String, BigDecimal> categoryTotals = new LinkedHashMap<>();
            BigDecimal worked = BigDecimal.ZERO;
            BigDecimal night = BigDecimal.ZERO;
            int late = 0;
            int early = 0;
            int exceptionDays = 0;
            int missing = 0;
            UUID orgUnit = null;
            for (LocalDate date : dates) {
                Day d = mine.get(date);
                if (d == null) {
                    cells.add("");
                    missing++;
                    continue;
                }
                cells.add(cell(d));
                if (d.orgUnitId() != null) {
                    orgUnit = d.orgUnitId();   // the unit of the last day: where the employee is now
                }
                for (CodeQty c : d.codes() == null ? List.<CodeQty>of() : d.codes()) {
                    if (c.code() == null || c.qty() == null || c.qty().signum() <= 0) {
                        continue;
                    }
                    codeTotals.merge(c.code(), c.qty(), BigDecimal::add);
                    categoryTotals.merge(categoryOf.getOrDefault(c.code(), UNMAPPED), c.qty(), BigDecimal::add);
                }
                worked = worked.add(nz(d.workedHours()));
                night = night.add(nz(d.nightHours()));
                late += d.lateMin();
                early += d.earlyMin();
                if (d.exceptions() != null && !d.exceptions().isEmpty()) {
                    exceptionDays++;
                }
            }
            rows.add(new Row(e, orgUnit, List.copyOf(cells), codeTotals, categoryTotals, scale(worked), late, early,
                    scale(night), exceptionDays, missing));
        }
        return rows;
    }

    /** The category columns of a grid: the statutory order first, then any other category found in the rows. */
    public static List<String> categories(Collection<Row> rows) {
        Set<String> found = new LinkedHashSet<>();
        rows.forEach(r -> found.addAll(r.categoryTotals().keySet()));
        List<String> out = new ArrayList<>();
        for (String c : CATEGORY_ORDER) {
            if (found.remove(c)) {
                out.add(c);
            }
        }
        out.addAll(found);
        return out;
    }

    /** Σ per category over all rows (the footer of the grid). */
    public static Map<String, BigDecimal> grandTotals(Collection<Row> rows) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String c : categories(rows)) {
            out.put(c, rows.stream().map(r -> r.categoryTotals().getOrDefault(c, BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
