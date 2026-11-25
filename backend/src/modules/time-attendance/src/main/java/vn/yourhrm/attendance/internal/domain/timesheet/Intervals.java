package vn.yourhrm.attendance.internal.domain.timesheet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Half-open minute intervals {@code [start, end)} on the timeline of one work date (minute 0 = local midnight of the
 * work date; values may be negative or exceed 1440 for night shifts). Lists are kept sorted and non-overlapping.
 */
final class Intervals {

    private Intervals() {
    }

    record Span(long start, long end) {

        long length() {
            return Math.max(0, end - start);
        }
    }

    static List<Span> normalise(List<Span> in) {
        List<Span> sorted = new ArrayList<>(in.stream().filter(s -> s.end() > s.start()).toList());
        sorted.sort(Comparator.comparingLong(Span::start));
        List<Span> out = new ArrayList<>();
        for (Span s : sorted) {
            if (!out.isEmpty() && s.start() <= out.getLast().end()) {
                Span last = out.removeLast();
                out.add(new Span(last.start(), Math.max(last.end(), s.end())));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    static List<Span> intersect(List<Span> a, List<Span> b) {
        List<Span> out = new ArrayList<>();
        for (Span x : normalise(a)) {
            for (Span y : normalise(b)) {
                long s = Math.max(x.start(), y.start());
                long e = Math.min(x.end(), y.end());
                if (e > s) {
                    out.add(new Span(s, e));
                }
            }
        }
        return normalise(out);
    }

    static List<Span> subtract(List<Span> a, List<Span> b) {
        List<Span> out = normalise(a);
        for (Span y : normalise(b)) {
            List<Span> next = new ArrayList<>();
            for (Span x : out) {
                if (y.end() <= x.start() || y.start() >= x.end()) {
                    next.add(x);
                    continue;
                }
                if (y.start() > x.start()) {
                    next.add(new Span(x.start(), y.start()));
                }
                if (y.end() < x.end()) {
                    next.add(new Span(y.end(), x.end()));
                }
            }
            out = next;
        }
        return out;
    }

    static long length(List<Span> spans) {
        return normalise(spans).stream().mapToLong(Span::length).sum();
    }
}
