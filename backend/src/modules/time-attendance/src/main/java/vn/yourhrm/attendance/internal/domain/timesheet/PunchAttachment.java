package vn.yourhrm.attendance.internal.domain.timesheet;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Punch;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Shift;

/**
 * Attaches punches to work dates (BR-ATT-030, US-ATT-023): a punch belongs to the work date whose planned shift
 * window {@code [start - before, end + after]} contains it (an overnight shift belongs to the date it starts); when
 * two windows contain it, to the shift it is nearer to. A punch outside every window belongs to its local calendar
 * date when that date has no shift (work on a rest day), otherwise it is unattached (reported on its local date).
 * Local times are taken in the employee's location zone, DST-safe through {@link java.time.ZonedDateTime}.
 */
public final class PunchAttachment {

    private PunchAttachment() {
    }

    /** {@code byDate}: attached punches per work date; {@code unattached}: stray punches per local date. */
    public record Result(Map<LocalDate, List<Punch>> byDate, Map<LocalDate, List<Punch>> unattached) {
    }

    /** Minutes of {@code at} on the timeline of {@code workDate} (0 = local midnight of the work date). */
    public static long minuteOf(Instant at, LocalDate workDate, ZoneId zone) {
        return Duration.between(workDate.atStartOfDay(zone).toInstant(), at).toSeconds() / 60;
    }

    /** Start / end minute of the shift on the timeline of its work date. */
    static long[] shiftSpan(Shift s) {
        long start = s.layout().start().toSecondOfDay() / 60;
        return new long[] {start, start + s.layout().spanMinutes()};
    }

    /** @param shifts the planned shift per date (absent or null = no shift that day) */
    public static Result attach(List<Punch> punches, Map<LocalDate, Shift> shifts, ZoneId zone) {
        Map<LocalDate, List<Punch>> by = new HashMap<>();
        Map<LocalDate, List<Punch>> stray = new HashMap<>();
        List<Punch> sorted = new ArrayList<>(punches);
        sorted.sort(Comparator.comparing(Punch::at).thenComparing(p -> String.valueOf(p.id())));
        for (Punch p : sorted) {
            LocalDate local = LocalDateTime.ofInstant(p.at(), zone).toLocalDate();
            LocalDate best = null;
            long bestDistance = Long.MAX_VALUE;
            for (LocalDate d : List.of(local.minusDays(1), local, local.plusDays(1))) {
                Shift s = shifts.get(d);
                if (s == null) {
                    continue;
                }
                long m = minuteOf(p.at(), d, zone);
                long[] span = shiftSpan(s);
                if (m < span[0] - s.windowBeforeMin() || m > span[1] + s.windowAfterMin()) {
                    continue;
                }
                long distance = m < span[0] ? span[0] - m : m > span[1] ? m - span[1] : 0;
                if (distance < bestDistance) {
                    best = d;
                    bestDistance = distance;
                }
            }
            if (best == null && shifts.get(local) == null) {
                best = local;
            }
            (best == null ? stray : by).computeIfAbsent(best == null ? local : best, k -> new ArrayList<>()).add(p);
        }
        return new Result(by, stray);
    }
}
