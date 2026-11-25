package vn.yourhrm.attendance.internal.domain.schedule;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The time layout of one shift (BR-ATT-010): local start / end times on the work date (the date the shift starts,
 * BRD §7.3), {@code end <= start} meaning the shift ends the next day (equal = a 24h shift), and its breaks. Minutes are
 * counted from the shift start, so a break after midnight of an overnight shift is placed correctly. Single block only:
 * split shifts are Phase 2 (BR-ATT-013).
 */
public record ShiftLayout(LocalTime start, LocalTime end, List<Break> breaks) {

    /** A break; {@code start}/{@code end} null for a flexible break (duration only). */
    public record Break(LocalTime start, LocalTime end, int durationMin, boolean paid, boolean flexible) {
    }

    public ShiftLayout {
        breaks = List.copyOf(breaks);
    }

    public boolean crossesMidnight() {
        return !end.isAfter(start);
    }

    /** Length of the shift in minutes (1440 for a 24h shift). */
    public int spanMinutes() {
        return offset(end) == 0 ? 1440 : offset(end);
    }

    /** Minutes of the shift that are working time: span minus unpaid breaks (paid breaks count, VN-03). */
    public int workingMinutes() {
        return spanMinutes() - breaks.stream().filter(b -> !b.paid()).mapToInt(Break::durationMin).sum();
    }

    /** Longest stretch of work without any break, in minutes (flexible breaks are not placed and not counted). */
    public int longestContinuousMinutes() {
        List<int[]> fixed = placedBreaks();
        int longest = 0;
        int cursor = 0;
        for (int[] b : fixed) {
            longest = Math.max(longest, b[0] - cursor);
            cursor = Math.max(cursor, b[1]);
        }
        return Math.max(longest, spanMinutes() - cursor);
    }

    /** Total break minutes, paid and unpaid, fixed and flexible. */
    public int breakMinutes() {
        return breaks.stream().mapToInt(Break::durationMin).sum();
    }

    /** Problems with the breaks (empty when consistent), as stable codes. */
    public List<String> breakProblems() {
        List<String> problems = new ArrayList<>();
        for (Break b : breaks) {
            if (b.durationMin() <= 0) {
                problems.add("break-duration-invalid");
            } else if (!b.flexible()) {
                if (b.start() == null || b.end() == null) {
                    problems.add("break-times-required");
                    continue;
                }
                int s = offset(b.start());
                int e = offset(b.end());
                if (e <= s) {
                    e = e + 1440;
                }
                if (s >= spanMinutes() || e > spanMinutes()) {
                    problems.add("break-outside-shift");
                } else if (e - s != b.durationMin()) {
                    problems.add("break-duration-mismatch");
                }
            } else if (b.start() != null || b.end() != null) {
                problems.add("flexible-break-has-times");
            }
        }
        List<int[]> placed = placedBreaks();
        for (int i = 1; i < placed.size(); i++) {
            if (placed.get(i)[0] < placed.get(i - 1)[1]) {
                problems.add("breaks-overlap");
                break;
            }
        }
        if (workingMinutes() <= 0) {
            problems.add("breaks-exceed-shift");
        }
        return problems.stream().distinct().toList();
    }

    /** Minutes from the shift start to {@code t} (0..1439), wrapping past midnight. */
    int offset(LocalTime t) {
        int m = (t.toSecondOfDay() - start.toSecondOfDay()) / 60;
        return Math.floorMod(m, 1440);
    }

    private List<int[]> placedBreaks() {
        List<int[]> out = new ArrayList<>();
        for (Break b : breaks) {
            if (!b.flexible() && b.start() != null && b.end() != null && b.durationMin() > 0) {
                int s = offset(b.start());
                out.add(new int[] {s, s + b.durationMin()});
            }
        }
        out.sort(Comparator.comparingInt(a -> a[0]));
        return out;
    }
}
