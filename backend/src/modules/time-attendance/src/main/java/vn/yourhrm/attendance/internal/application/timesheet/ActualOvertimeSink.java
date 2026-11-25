package vn.yourhrm.attendance.internal.application.timesheet;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Hand-over of a day's actual OT candidate time to the overtime stream (BRD §4.5): daily processing pushes, inside its
 * T&amp;A transaction and right after it wrote a changed day, the presence intervals outside the planned shift (all
 * presence on a rest day or holiday) — exactly what {@code daily_timesheet.ot_candidate_min} sums. An empty list means
 * no actual OT. Implementations classify the minutes, are idempotent per employment × work date and never call back
 * into daily processing. Optional: without an implementation nothing is pushed.
 */
public interface ActualOvertimeSink {

    record Interval(Instant start, Instant end) {
    }

    void recordActual(UUID employmentId, LocalDate workDate, List<Interval> actualOt);
}
