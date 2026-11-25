package vn.yourhrm.attendance.internal.domain.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A half-open date range {@code [from, to)} as in Pattern A ({@code valid_to} exclusive); {@code to == null} means
 * open-ended.
 */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        Objects.requireNonNull(from, "from");
        if (to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("empty range " + from + " .. " + to);
        }
    }

    public boolean contains(LocalDate d) {
        return !d.isBefore(from) && (to == null || d.isBefore(to));
    }

    public boolean overlaps(DateRange o) {
        return (o.to == null || from.isBefore(o.to)) && (to == null || o.from.isBefore(to));
    }

    /** The common part of both ranges, empty when they do not overlap. */
    public java.util.Optional<DateRange> intersect(DateRange o) {
        if (!overlaps(o)) {
            return java.util.Optional.empty();
        }
        LocalDate f = from.isAfter(o.from) ? from : o.from;
        LocalDate t = to == null ? o.to : o.to == null ? to : (to.isBefore(o.to) ? to : o.to);
        return java.util.Optional.of(new DateRange(f, t));
    }

    /** {@code this} minus {@code cut}: zero, one or two pieces. */
    public List<DateRange> minus(DateRange cut) {
        if (!overlaps(cut)) {
            return List.of(this);
        }
        List<DateRange> out = new ArrayList<>(2);
        if (cut.from.isAfter(from)) {
            out.add(new DateRange(from, cut.from));
        }
        if (cut.to != null && (to == null || cut.to.isBefore(to))) {
            out.add(new DateRange(cut.to, to));
        }
        return out;
    }

    /** {@code this} minus every range of {@code cuts}. */
    public List<DateRange> minus(List<DateRange> cuts) {
        List<DateRange> pieces = List.of(this);
        for (DateRange c : cuts) {
            List<DateRange> next = new ArrayList<>();
            for (DateRange p : pieces) {
                next.addAll(p.minus(c));
            }
            pieces = next;
        }
        return pieces;
    }
}
