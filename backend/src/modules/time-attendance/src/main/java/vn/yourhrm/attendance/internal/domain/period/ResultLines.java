package vn.yourhrm.attendance.internal.domain.period;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the Attendance Result lines of one employment in one period (BRD §7.3, BR-ATT-076) from its daily timesheet
 * days, its paid overtime and its standard days, and the retro differences against what was already handed over
 * (BR-ATT-071, §7.3 rules b and d). Pure: no I/O.
 *
 * <ul>
 *   <li><b>Segments</b> (§7.3 "mức chi tiết", P10): a new segment starts on a day whose assignment line, legal entity,
 *       org unit, pay group, cost centre, work schedule (the T&amp;A schedule pattern assigned to the line), employee
 *       group (category) or FTE differs from the day before ({@link SegmentKeys}), and where employment starts or ends
 *       (NOT_EMPLOYED runs are their own segment, without standard days).</li>
 *   <li><b>Categories from attendance codes:</b> each code quantity goes to the code's payroll category;
 *       PAID_LEAVE_DAYS, SI_LEAVE_DAYS and UNPAID_DAYS carry the code's sub-code ({@code PAID_LEAVE_DAYS.ANNUAL}), the
 *       others are totals (WORKED_DAYS includes trips and WFH, HOLIDAY_DAYS includes substitute days). TRIP and WFH
 *       codes add TRIP_DAYS.&lt;DOMESTIC|OVERSEAS&gt; (§7.3 item 14, from the trip code's sub-code: OVERSEAS, else
 *       DOMESTIC) / WFH_DAYS; codes counting as SI non-working add SI_NONWORK_DAYS (VN-18).</li>
 *   <li><b>MEAL_ELIGIBLE_DAYS</b> (§7.3 item 13) under the tenant rule ({@link MealRule}): one per employed day with
 *       worked hours at or above the rule's minimum, or with a trip / WFH code when the rule counts those days.</li>
 *   <li><b>Dated quantities</b> ({@link Dated}): LEAVE_ENCASH_DAYS of leave paid out by the year-end / carry-expiry
 *       runs (§7.3 item 15, ATT-P-06), on the employed segment covering their ledger date.</li>
 *   <li><b>Day facts:</b> NIGHT_HOURS, LATE_MIN / EARLY_MIN and their counts, MISSING_PUNCH_COUNT, and
 *       SHIFT_COUNT.&lt;shift group&gt; for a day with worked hours.</li>
 *   <li><b>Overtime:</b> OT_HOURS.&lt;category&gt; = paid minutes / 60, the night-after-day-OT flag kept apart.</li>
 *   <li>Quantities are summed exactly and rounded once per line to 2 decimals (HALF_UP). Zero lines are left out,
 *       except STD_DAYS / STD_HOURS of an employed segment.</li>
 * </ul>
 */
public final class ResultLines {

    public static final String RULE_VERSION = "ATT-RESULT-2";
    public static final String RETRO_PREFIX = "RETRO.";
    /** Destinations of TRIP_DAYS (§7.3 item 14); a trip code with any other sub-code counts as DOMESTIC. */
    public static final String TRIP_DOMESTIC = "DOMESTIC";
    public static final String TRIP_OVERSEAS = "OVERSEAS";

    private static final Set<String> TYPED = Set.of("PAID_LEAVE_DAYS", "SI_LEAVE_DAYS", "UNPAID_DAYS");
    private static final Pattern SUB = Pattern.compile("[A-Za-z0-9_-]+");
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private ResultLines() {
    }

    /** What a code means for the result ({@code att.attendance_code}). */
    public record CodeInfo(String kind, String category, String subCode, boolean siNonwork) {
    }

    /** A code and its quantity on a day. */
    public record CodeQty(String code, BigDecimal qty) {
    }

    /**
     * The segment keys beyond the assignment line's placement (BRD §7.3): the schedule pattern assigned to the line on
     * the day ({@code att.schedule_assignment}), the employee group = category ({@code
     * att.ext_hr_employment_classification}, else the line's) and the line's FTE ({@code att.ext_hr_assignment}).
     * FTE compares by value (1.0 = 1.0000).
     */
    public record SegmentKeys(UUID schedulePatternId, String employeeGroup, BigDecimal fte) {

        public static final SegmentKeys NONE = new SegmentKeys(null, null, null);

        public SegmentKeys {
            if (fte != null) {
                fte = fte.stripTrailingZeros();
                if (fte.scale() < 0) {
                    fte = fte.setScale(0);
                }
            }
        }
    }

    /** One day of the employment, from {@code att.daily_timesheet} or recomputed in memory. */
    public record DayFacts(LocalDate date, boolean employed, UUID assignmentKey, UUID legalEntityId, UUID orgUnitId,
            UUID payGroupId, UUID costCentreId, List<CodeQty> codes, BigDecimal nightHours, int lateMin,
            int earlyMin, List<String> exceptions, BigDecimal workedHours, String shiftGroupCode, SegmentKeys keys) {

        public DayFacts {
            codes = List.copyOf(codes);
            exceptions = List.copyOf(exceptions);
            keys = keys == null ? SegmentKeys.NONE : keys;
        }

        /** A day without schedule / group / FTE keys. */
        public DayFacts(LocalDate date, boolean employed, UUID assignmentKey, UUID legalEntityId, UUID orgUnitId,
                UUID payGroupId, UUID costCentreId, List<CodeQty> codes, BigDecimal nightHours, int lateMin,
                int earlyMin, List<String> exceptions, BigDecimal workedHours, String shiftGroupCode) {
            this(date, employed, assignmentKey, legalEntityId, orgUnitId, payGroupId, costCentreId, codes, nightHours,
                    lateMin, earlyMin, exceptions, workedHours, shiftGroupCode, SegmentKeys.NONE);
        }

        public DayFacts withPayGroup(UUID payGroup) {
            return new DayFacts(date, employed, assignmentKey, legalEntityId, orgUnitId, payGroup, costCentreId, codes,
                    nightHours, lateMin, earlyMin, exceptions, workedHours, shiftGroupCode, keys);
        }
    }

    /**
     * The tenant rule of MEAL_ELIGIBLE_DAYS (§7.3 item 13): the minimum worked hours of an eligible day, and whether
     * trip / WFH days are eligible whatever their worked hours.
     */
    public record MealRule(BigDecimal minWorkedHours, boolean tripDaysCount, boolean wfhDaysCount) {
    }

    /** A quantity of a category on a date, placed on the employed segment covering it (ATT-P-06 paid-out leave). */
    public record Dated(LocalDate date, String category, String unit, BigDecimal quantity) {
    }

    /** Paid overtime of a work date and category ({@code att.overtime_result}). */
    public record Overtime(LocalDate date, String category, boolean precededByDayOt, int paidMinutes) {
    }

    /** Inclusive dates. */
    public record Segment(LocalDate from, LocalDate to, boolean employed, UUID assignmentKey, UUID legalEntityId,
            UUID orgUnitId, UUID payGroupId, UUID costCentreId, SegmentKeys keys) {

        public Segment {
            keys = keys == null ? SegmentKeys.NONE : keys;
        }

        public Segment(LocalDate from, LocalDate to, boolean employed, UUID assignmentKey, UUID legalEntityId,
                UUID orgUnitId, UUID payGroupId, UUID costCentreId) {
            this(from, to, employed, assignmentKey, legalEntityId, orgUnitId, payGroupId, costCentreId,
                    SegmentKeys.NONE);
        }

        boolean covers(LocalDate d) {
            return !d.isBefore(from) && !d.isAfter(to);
        }
    }

    /** Standard days of a segment (by its {@code from}) and of the whole period, for proration. */
    public record Standard(Map<LocalDate, BigDecimal[]> bySegmentFrom, BigDecimal periodDays, BigDecimal periodHours) {
    }

    public record Line(Segment segment, String category, String unit, BigDecimal quantity, BigDecimal periodQuantity,
            boolean precededByDayOt) {
    }

    /** A line already handed over for an original period: the locked version's, or a live retro line. */
    public record Existing(LocalDate segmentFrom, LocalDate segmentTo, UUID assignmentKey, UUID legalEntityId,
            UUID orgUnitId, UUID payGroupId, UUID costCentreId, String category, String unit, BigDecimal quantity,
            boolean precededByDayOt, SegmentKeys keys) {

        public Existing {
            keys = keys == null ? SegmentKeys.NONE : keys;
        }

        public Existing(LocalDate segmentFrom, LocalDate segmentTo, UUID assignmentKey, UUID legalEntityId,
                UUID orgUnitId, UUID payGroupId, UUID costCentreId, String category, String unit, BigDecimal quantity,
                boolean precededByDayOt) {
            this(segmentFrom, segmentTo, assignmentKey, legalEntityId, orgUnitId, payGroupId, costCentreId, category,
                    unit, quantity, precededByDayOt, SegmentKeys.NONE);
        }
    }

    /** A signed difference, keyed by the original segment. */
    public record Delta(Segment segment, String category, String unit, BigDecimal quantity, boolean precededByDayOt) {
    }

    // ------------------------------------------------------------------------------------------------ segments

    public static List<Segment> segments(List<DayFacts> days) {
        List<DayFacts> sorted = days.stream().sorted(Comparator.comparing(DayFacts::date)).toList();
        List<Segment> out = new ArrayList<>();
        DayFacts start = null;
        DayFacts prev = null;
        for (DayFacts d : sorted) {
            if (start != null && (!sameSegment(prev, d) || !prev.date().plusDays(1).equals(d.date()))) {
                out.add(segment(start, prev.date()));
                start = null;
            }
            if (start == null) {
                start = d;
            }
            prev = d;
        }
        if (start != null) {
            out.add(segment(start, prev.date()));
        }
        return out;
    }

    private static boolean sameSegment(DayFacts a, DayFacts b) {
        return a.employed() == b.employed() && Objects.equals(a.assignmentKey(), b.assignmentKey())
                && Objects.equals(a.legalEntityId(), b.legalEntityId()) && Objects.equals(a.orgUnitId(), b.orgUnitId())
                && Objects.equals(a.payGroupId(), b.payGroupId()) && Objects.equals(a.costCentreId(), b.costCentreId())
                && a.keys().equals(b.keys());
    }

    private static Segment segment(DayFacts d, LocalDate to) {
        return new Segment(d.date(), to, d.employed(), d.assignmentKey(), d.legalEntityId(), d.orgUnitId(),
                d.payGroupId(), d.costCentreId(), d.keys());
    }

    // ------------------------------------------------------------------------------------------------ lines

    /**
     * @param standard null when the period has no standard-day method yet (no STD lines)
     */
    public static List<Line> lines(List<DayFacts> days, Map<String, CodeInfo> codes, List<Overtime> overtime,
            Standard standard) {
        return lines(days, codes, overtime, standard, null, List.of());
    }

    /**
     * @param standard null when the period has no standard-day method yet (no STD lines)
     * @param meal     null: no MEAL_ELIGIBLE_DAYS line
     * @param dated    quantities placed on the employed segment of their date (LEAVE_ENCASH_DAYS of ATT-P-06)
     */
    public static List<Line> lines(List<DayFacts> days, Map<String, CodeInfo> codes, List<Overtime> overtime,
            Standard standard, MealRule meal, List<Dated> dated) {
        List<Segment> segments = segments(days);
        Map<Key, BigDecimal> sums = new LinkedHashMap<>();
        Map<Key, String> units = new LinkedHashMap<>();
        for (Segment s : segments) {
            if (s.employed() && standard != null) {
                BigDecimal[] std = standard.bySegmentFrom().get(s.from());
                add(sums, units, new Key(s, "STD_DAYS", false), "DAY", std == null ? BigDecimal.ZERO : std[0]);
                add(sums, units, new Key(s, "STD_HOURS", false), "HOUR", std == null ? BigDecimal.ZERO : std[1]);
            }
        }
        for (DayFacts d : days) {
            Segment s = segmentOf(segments, d.date());
            boolean mealByCode = false;
            for (CodeQty c : d.codes()) {
                CodeInfo info = codes.get(c.code());
                if (info == null || c.qty() == null || c.qty().signum() == 0) {
                    continue;
                }
                add(sums, units, new Key(s, category(info), false), "DAY", c.qty());
                if ("TRIP".equals(info.kind())) {
                    add(sums, units, new Key(s, "TRIP_DAYS." + tripDestination(info), false), "DAY", c.qty());
                    mealByCode |= meal != null && meal.tripDaysCount();
                } else if ("WFH".equals(info.kind())) {
                    add(sums, units, new Key(s, "WFH_DAYS", false), "DAY", c.qty());
                    mealByCode |= meal != null && meal.wfhDaysCount();
                }
                if (info.siNonwork()) {
                    add(sums, units, new Key(s, "SI_NONWORK_DAYS", false), "DAY", c.qty());
                }
            }
            if (meal != null && d.employed() && (mealByCode || mealByHours(meal, d.workedHours()))) {
                add(sums, units, new Key(s, "MEAL_ELIGIBLE_DAYS", false), "DAY", BigDecimal.ONE);
            }
            add(sums, units, new Key(s, "NIGHT_HOURS", false), "HOUR", nz(d.nightHours()));
            add(sums, units, new Key(s, "LATE_MIN", false), "MINUTE", BigDecimal.valueOf(d.lateMin()));
            add(sums, units, new Key(s, "EARLY_MIN", false), "MINUTE", BigDecimal.valueOf(d.earlyMin()));
            add(sums, units, new Key(s, "LATE_COUNT", false), "COUNT", d.lateMin() > 0 ? BigDecimal.ONE : null);
            add(sums, units, new Key(s, "EARLY_COUNT", false), "COUNT", d.earlyMin() > 0 ? BigDecimal.ONE : null);
            boolean missing = d.exceptions().contains("MISSING_IN") || d.exceptions().contains("MISSING_OUT");
            add(sums, units, new Key(s, "MISSING_PUNCH_COUNT", false), "COUNT", missing ? BigDecimal.ONE : null);
            if (d.shiftGroupCode() != null && SUB.matcher(d.shiftGroupCode()).matches()
                    && nz(d.workedHours()).signum() > 0) {
                add(sums, units, new Key(s, "SHIFT_COUNT." + d.shiftGroupCode(), false), "COUNT", BigDecimal.ONE);
            }
        }
        for (Overtime o : overtime) {
            if (o.paidMinutes() <= 0 || segments.isEmpty()) {
                continue;
            }
            Segment s = segmentOf(segments, o.date());
            add(sums, units, new Key(s, "OT_HOURS." + o.category(), o.precededByDayOt()), "HOUR",
                    BigDecimal.valueOf(o.paidMinutes()).divide(SIXTY, 10, RoundingMode.HALF_UP));
        }
        for (Dated x : dated) {
            if (x.quantity() == null || x.quantity().signum() == 0 || segments.isEmpty()) {
                continue;
            }
            add(sums, units, new Key(employedSegmentOf(segments, x.date()), x.category(), false), x.unit(),
                    x.quantity());
        }
        List<Line> out = new ArrayList<>();
        sums.forEach((k, v) -> {
            BigDecimal q = v.setScale(2, RoundingMode.HALF_UP);
            boolean std = k.category().equals("STD_DAYS") || k.category().equals("STD_HOURS");
            if (q.signum() == 0 && !std) {
                return;
            }
            BigDecimal periodQ = !std || standard == null ? null
                    : (k.category().equals("STD_DAYS") ? standard.periodDays() : standard.periodHours());
            out.add(new Line(k.segment(), k.category(), units.get(k), q,
                    periodQ == null ? null : periodQ.setScale(2, RoundingMode.HALF_UP), k.preceded()));
        });
        return out;
    }

    /** The result category of a code (see the class comment). */
    public static String category(CodeInfo info) {
        if (TYPED.contains(info.category()) && info.subCode() != null && SUB.matcher(info.subCode()).matches()) {
            return info.category() + "." + info.subCode();
        }
        return info.category();
    }

    /** TRIP_DAYS sub-code of a trip code: OVERSEAS when its sub-code says so, else DOMESTIC (§7.3 item 14). */
    public static String tripDestination(CodeInfo info) {
        return TRIP_OVERSEAS.equalsIgnoreCase(info.subCode()) ? TRIP_OVERSEAS : TRIP_DOMESTIC;
    }

    /** A day with worked hours at or above the rule's minimum (a minimum of 0 still needs some worked time). */
    private static boolean mealByHours(MealRule meal, BigDecimal worked) {
        BigDecimal w = nz(worked);
        return w.signum() > 0 && w.compareTo(nz(meal.minWorkedHours())) >= 0;
    }

    /** The segment of the date when employed, else the nearest employed segment before it, else the first employed. */
    private static Segment employedSegmentOf(List<Segment> segments, LocalDate d) {
        Segment s = segmentOf(segments, d);
        if (s.employed()) {
            return s;
        }
        Segment best = null;
        for (Segment x : segments) {
            if (x.employed() && (best == null || !x.from().isAfter(d))) {
                best = x;
            }
        }
        return best == null ? s : best;
    }

    private static Segment segmentOf(List<Segment> segments, LocalDate d) {
        for (Segment s : segments) {
            if (s.covers(d)) {
                return s;
            }
        }
        // a date outside the days (OT cut-off): the nearest segment before it, else the first
        Segment best = segments.getFirst();
        for (Segment s : segments) {
            if (!s.from().isAfter(d)) {
                best = s;
            }
        }
        return best;
    }

    private record Key(Segment segment, String category, boolean preceded) {
    }

    private static void add(Map<Key, BigDecimal> sums, Map<Key, String> units, Key k, String unit, BigDecimal v) {
        if (v == null) {
            return;
        }
        sums.merge(k, v, BigDecimal::add);
        units.putIfAbsent(k, unit);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ------------------------------------------------------------------------------------------------ retro

    /**
     * What changed for an original period: {@code fresh} (lines recomputed now) minus {@code existing} (the locked
     * version's lines plus retro lines already issued, with their {@code RETRO.} prefix removed), per original segment
     * start, category and night flag. Non-zero differences only; STD lines take part like any other.
     */
    public static List<Delta> diff(List<Line> fresh, List<Existing> existing) {
        record K(LocalDate from, String category, boolean preceded) {
        }
        Map<K, BigDecimal> qty = new LinkedHashMap<>();
        Map<K, Segment> seg = new LinkedHashMap<>();
        Map<K, String> unit = new LinkedHashMap<>();
        for (Line l : fresh) {
            K k = new K(l.segment().from(), l.category(), l.precededByDayOt());
            qty.merge(k, l.quantity(), BigDecimal::add);
            seg.putIfAbsent(k, l.segment());
            unit.putIfAbsent(k, l.unit());
        }
        for (Existing e : existing) {
            String cat = e.category().startsWith(RETRO_PREFIX) ? e.category().substring(RETRO_PREFIX.length())
                    : e.category();
            K k = new K(e.segmentFrom(), cat, e.precededByDayOt());
            qty.merge(k, e.quantity().negate(), BigDecimal::add);
            seg.putIfAbsent(k, new Segment(e.segmentFrom(), e.segmentTo(), true, e.assignmentKey(), e.legalEntityId(),
                    e.orgUnitId(), e.payGroupId(), e.costCentreId(), e.keys()));
            unit.putIfAbsent(k, e.unit());
        }
        List<Delta> out = new ArrayList<>();
        qty.forEach((k, v) -> {
            BigDecimal q = v.setScale(2, RoundingMode.HALF_UP);
            if (q.signum() != 0) {
                out.add(new Delta(seg.get(k), k.category(), unit.get(k), q, k.preceded()));
            }
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------------ checksum

    /** A canonical line of the checksum (order-independent: callers sort). */
    public static String canonical(UUID employmentId, LocalDate segmentFrom, LocalDate segmentTo, String category,
            String unit, BigDecimal quantity, BigDecimal periodQuantity, boolean preceded, UUID originalPeriodId,
            LocalDate originalSegmentFrom) {
        return employmentId + "|" + segmentFrom + "|" + segmentTo + "|" + category + "|" + unit + "|"
                + quantity.setScale(2, RoundingMode.HALF_UP).toPlainString() + "|"
                + (periodQuantity == null ? "" : periodQuantity.setScale(2, RoundingMode.HALF_UP).toPlainString())
                + "|" + preceded + "|" + (originalPeriodId == null ? "" : originalPeriodId) + "|"
                + (originalSegmentFrom == null ? "" : originalSegmentFrom);
    }

    /** SHA-256 hex over the sorted canonical lines joined by new lines. */
    public static String checksum(List<String> canonicalLines) {
        List<String> sorted = canonicalLines.stream().sorted().toList();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
