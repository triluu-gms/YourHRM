package vn.yourhrm.attendance.internal.domain.overtime;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Splits overtime minutes of one work date into the six legal categories (BR-ATT-041) and flags night overtime that
 * follows day overtime of the same work date (BR-ATT-042, ND 145 Art. 57(3) [verify-legal]).
 *
 * <p>Each minute falls into exactly one category: the day type of the <em>calendar date of that minute</em> (work day,
 * weekly rest day, public / paid holiday) and whether the minute lies in the night window. Night work in normal hours
 * (NW) is not overtime and is not produced here. Intervals are local date-times, truncated to the minute; overlapping
 * intervals count once.
 */
public final class OvertimeClassifier {

    public enum DayType { WORKDAY, REST_DAY, HOLIDAY }

    public enum Category {
        OT_WD("OT-WD"), OT_RD("OT-RD"), OT_PH("OT-PH"), OT_WD_N("OT-WD-N"), OT_RD_N("OT-RD-N"), OT_PH_N("OT-PH-N");

        private final String code;

        Category(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public boolean night() {
            return code.endsWith("-N");
        }

        public static Category of(DayType day, boolean night) {
            return switch (day) {
                case WORKDAY -> night ? OT_WD_N : OT_WD;
                case REST_DAY -> night ? OT_RD_N : OT_RD;
                case HOLIDAY -> night ? OT_PH_N : OT_PH;
            };
        }

        public static Category ofCode(String code) {
            for (Category c : values()) {
                if (c.code.equals(code)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("unknown OT category " + code);
        }
    }

    /** {@code [start, end)} in local time of the employee's location. */
    public record Interval(LocalDateTime start, LocalDateTime end) {

        public Interval {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }

    /** Minutes of one category; {@code precededByDayOt} only on night categories. */
    public record Bucket(Category category, int minutes, boolean precededByDayOt) {
    }

    /** The night window, e.g. 22:00-06:00 (crosses midnight when {@code end <= start}). */
    public record NightWindow(LocalTime start, LocalTime end) {

        public NightWindow {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }

        public boolean contains(LocalTime t) {
            return start.isBefore(end) ? !t.isBefore(start) && t.isBefore(end) : !t.isBefore(start) || t.isBefore(end);
        }
    }

    /** Longest interval accepted (one work date never holds more than a day of overtime). */
    public static final int MAX_MINUTES = 24 * 60;

    private OvertimeClassifier() {
    }

    public static List<Bucket> classify(List<Interval> intervals, Function<LocalDate, DayType> dayType,
            NightWindow night) {
        List<LocalDateTime[]> spans = merge(intervals);
        Map<Category, Integer> minutes = new EnumMap<>(Category.class);
        Map<Category, Boolean> preceded = new EnumMap<>(Category.class);
        boolean dayOtSeen = false;
        for (LocalDateTime[] s : spans) {
            for (LocalDateTime m = s[0]; m.isBefore(s[1]); m = m.plusMinutes(1)) {
                boolean isNight = night.contains(m.toLocalTime());
                Category c = Category.of(dayType.apply(m.toLocalDate()), isNight);
                minutes.merge(c, 1, Integer::sum);
                if (isNight) {
                    preceded.merge(c, dayOtSeen, Boolean::logicalOr);
                } else {
                    dayOtSeen = true;
                }
            }
        }
        List<Bucket> out = new ArrayList<>();
        minutes.forEach((c, n) -> out.add(new Bucket(c, n, c.night() && preceded.getOrDefault(c, false))));
        return out;
    }

    /** Total minutes of the intervals, overlaps counted once. */
    public static int minutes(List<Interval> intervals) {
        return merge(intervals).stream().mapToInt(s -> (int) ChronoUnit.MINUTES.between(s[0], s[1])).sum();
    }

    private static List<LocalDateTime[]> merge(List<Interval> intervals) {
        List<LocalDateTime[]> sorted = new ArrayList<>();
        for (Interval i : intervals) {
            LocalDateTime s = i.start().truncatedTo(ChronoUnit.MINUTES);
            LocalDateTime e = i.end().truncatedTo(ChronoUnit.MINUTES);
            if (e.isAfter(s)) {
                if (ChronoUnit.MINUTES.between(s, e) > MAX_MINUTES) {
                    throw new IllegalArgumentException("an overtime interval is longer than 24 hours");
                }
                sorted.add(new LocalDateTime[] {s, e});
            }
        }
        sorted.sort(Comparator.comparing(a -> a[0]));
        List<LocalDateTime[]> out = new ArrayList<>();
        for (LocalDateTime[] s : sorted) {
            if (!out.isEmpty() && !s[0].isAfter(out.getLast()[1])) {
                LocalDateTime[] last = out.getLast();
                if (s[1].isAfter(last[1])) {
                    last[1] = s[1];
                }
            } else {
                out.add(s);
            }
        }
        return out;
    }
}
