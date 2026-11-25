package vn.yourhrm.attendance.internal.domain.schedule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * STD_DAYS / STD_HOURS of one employment for one period (BR-ATT-016, US-ATT-012), with the pay group's method owned by
 * Payroll (BR-PAY-009); T&amp;A never configures a method. Result category 1 of BRD §7.3: one value per segment plus the
 * full-period value used for proration.
 *
 * <p>Inputs per day of the period: the schedule that applies (for days outside the employment, the nearest schedule of
 * the employment, so that a mid-period hire still gets the full-period value) and the segment the day belongs to
 * ({@code null} = not employed that day). A scheduled working day counts with its day value even when it is a public
 * holiday: paid holidays are paid days (BR-PAY-009).
 *
 * <p>Method semantics (PM decision D1, 2026-11-19):
 * <ul>
 *   <li>{@code SCHEDULE_DAYS}: days = Σ day value of WORK days; hours = Σ standard hours of WORK days;</li>
 *   <li>{@code CALENDAR_DAYS}: days = calendar days; hours as for SCHEDULE_DAYS;</li>
 *   <li>{@code FIXED_DAYS}: period days = the fixed value; a segment gets the share of its calendar days in the
 *       period's calendar days; hours as for SCHEDULE_DAYS;</li>
 *   <li>{@code FIXED_HOURS}: period hours = the fixed value; a segment gets the share of its calendar days in the
 *       period's calendar days; days as for SCHEDULE_DAYS.</li>
 * </ul>
 * Values are rounded half-up to 4 decimals ({@code sys.quantity}); segment shares are computed from unrounded sums
 * and are not adjusted to add up exactly to the fixed value.
 */
public final class StandardDays {

    public static final Set<String> METHODS = Set.of("FIXED_DAYS", "SCHEDULE_DAYS", "CALENDAR_DAYS", "FIXED_HOURS");

    private static final int SCALE = 4;

    /** One day of the period. */
    public record Day(LocalDate date, UUID segmentId, boolean work, BigDecimal dayValue, BigDecimal stdHours) {
    }

    public record SegmentValue(UUID segmentId, LocalDate from, LocalDate to, BigDecimal stdDays,
            BigDecimal stdHours) {
    }

    public record Result(String method, BigDecimal methodValue, BigDecimal periodStdDays, BigDecimal periodStdHours,
            List<SegmentValue> segments) {
    }

    private StandardDays() {
    }

    public static Result compute(String method, BigDecimal value, List<Day> days) {
        Objects.requireNonNull(method, "method");
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("unknown standard-day method " + method);
        }
        boolean fixed = method.startsWith("FIXED_");
        if (fixed && (value == null || value.signum() <= 0)) {
            throw new IllegalArgumentException(method + " needs a positive value");
        }
        Sums period = new Sums();
        Map<UUID, Sums> bySegment = new LinkedHashMap<>();
        for (Day d : days) {
            period.add(d);
            if (d.segmentId() != null) {
                bySegment.computeIfAbsent(d.segmentId(), k -> new Sums()).add(d);
            }
        }
        BigDecimal periodDays = switch (method) {
            case "CALENDAR_DAYS" -> BigDecimal.valueOf(period.calendarDays);
            case "FIXED_DAYS" -> value;
            default -> period.workDays;
        };
        BigDecimal periodHours = "FIXED_HOURS".equals(method) ? value : period.workHours;
        List<SegmentValue> segments = new ArrayList<>();
        for (Map.Entry<UUID, Sums> e : bySegment.entrySet()) {
            Sums s = e.getValue();
            BigDecimal segDays = switch (method) {
                case "CALENDAR_DAYS" -> BigDecimal.valueOf(s.calendarDays);
                case "FIXED_DAYS" -> share(value, s.calendarDays, period.calendarDays);
                default -> s.workDays;
            };
            BigDecimal segHours = "FIXED_HOURS".equals(method) ? share(value, s.calendarDays, period.calendarDays)
                    : s.workHours;
            segments.add(new SegmentValue(e.getKey(), s.first, s.last, round(segDays), round(segHours)));
        }
        return new Result(method, value, round(periodDays), round(periodHours), segments);
    }

    private static BigDecimal share(BigDecimal total, long part, long whole) {
        if (whole == 0) {
            return BigDecimal.ZERO;
        }
        return total.multiply(BigDecimal.valueOf(part)).divide(BigDecimal.valueOf(whole), 10, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static final class Sums {
        long calendarDays;
        BigDecimal workDays = BigDecimal.ZERO;
        BigDecimal workHours = BigDecimal.ZERO;
        LocalDate first;
        LocalDate last;

        void add(Day d) {
            calendarDays++;
            if (d.work()) {
                workDays = workDays.add(d.dayValue());
                workHours = workHours.add(d.stdHours());
            }
            if (first == null || d.date().isBefore(first)) {
                first = d.date();
            }
            if (last == null || d.date().isAfter(last)) {
                last = d.date();
            }
        }
    }
}
