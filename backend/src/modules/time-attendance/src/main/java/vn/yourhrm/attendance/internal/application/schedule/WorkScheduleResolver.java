package vn.yourhrm.attendance.internal.application.schedule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.HolidayCalendars;
import vn.yourhrm.attendance.api.HolidayCalendars.Holiday;
import vn.yourhrm.attendance.api.WorkSchedules;
import vn.yourhrm.attendance.internal.domain.holiday.SubstituteHolidays;
import vn.yourhrm.attendance.internal.domain.schedule.StandardDays;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.AssignmentRow;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.EmploymentDates;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.PatternDay;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.PatternRow;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.ShiftRow;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * {@link WorkSchedules} (ATT-P-16 schedule part) and the per-day inputs of the standard days (BR-ATT-016).
 *
 * <p>For each day: the schedule assignment covering it (several lines: the one that started last wins), the pattern
 * day ({@code (date - anchor) mod cycle}), the shift, the holiday of the calendar resolved for the line's legal entity
 * and location on that day, and the substitute day derived against the employee's own weekly rest days (BR-ATT-003):
 * a public paid holiday on a REST day moves to the next scheduled WORK day that is not itself a holiday. Holidays up to
 * {@link #LOOKBACK_DAYS} days before the range are considered, so a substitute inside the range is found.
 */
@Service
public class WorkScheduleResolver implements WorkSchedules {

    static final int LOOKBACK_DAYS = 31;

    private final ScheduleAssignmentStore assignments;
    private final ShiftPatternStore patterns;
    private final HolidayCalendars holidays;

    WorkScheduleResolver(ScheduleAssignmentStore assignments, ShiftPatternStore patterns,
            HolidayCalendars holidays) {
        this.assignments = assignments;
        this.patterns = patterns;
        this.holidays = holidays;
    }

    @Override
    @AttTransactional(readOnly = true)
    public List<PlannedDay> plan(UUID employmentId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(employmentId, "employmentId");
        requireRange(from, to);
        Context ctx = load(employmentId, from.minusDays(LOOKBACK_DAYS), to);
        List<PlannedDay> window = new ArrayList<>();
        for (LocalDate d = ctx.from; !d.isAfter(to); d = d.plusDays(1)) {
            window.add(day(ctx, d));
        }
        // BR-ATT-003: per-employee substitute days
        Map<LocalDate, PlannedDay> byDate = new HashMap<>();
        window.forEach(p -> byDate.put(p.date(), p));
        List<LocalDate> publicHolidays = window.stream()
                .filter(p -> p.scheduled() && p.holiday() != null && "PUBLIC".equals(p.holiday().dayType())
                        && p.holiday().paid())
                .map(PlannedDay::date).toList();
        Map<LocalDate, LocalDate> substitutes = new HashMap<>();
        SubstituteHolidays.derive(publicHolidays,
                d -> byDate.containsKey(d) && "REST".equals(byDate.get(d).dayKind()),
                d -> {
                    PlannedDay p = byDate.get(d);
                    return p != null && "WORK".equals(p.dayKind()) && p.holiday() == null;
                })
                .forEach(s -> substitutes.put(s.date(), s.substituteFor()));
        return window.stream().filter(p -> !p.date().isBefore(from))
                .map(p -> substitutes.containsKey(p.date()) ? withSubstitute(p, substitutes.get(p.date())) : p)
                .toList();
    }

    /**
     * The days of {@code [from, to]} for {@link StandardDays}: the segment is the schedule assignment when the
     * employment covers the day and a schedule exists, else null; days without a schedule (before the first or after
     * the last schedule, or between two) take the pattern of the nearest earlier schedule, else the nearest later one,
     * so the full-period value is complete.
     */
    @AttTransactional(readOnly = true)
    public List<StandardDays.Day> standardDayInputs(UUID employmentId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        Context ctx = load(employmentId, from, to);
        Optional<EmploymentDates> employment = assignments.employment(employmentId);
        List<StandardDays.Day> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            LocalDate day = d;
            AssignmentRow covering = covering(ctx, d);
            AssignmentRow source = covering != null ? covering : nearest(ctx, d);
            boolean employed = covering != null && employment
                    .map(e -> !day.isBefore(e.hireDate()) && (e.terminationDate() == null
                            || !day.isAfter(e.terminationDate())) && !"CANCELLED".equals(e.status()))
                    .orElse(true);
            if (source == null) {
                out.add(new StandardDays.Day(d, null, false, BigDecimal.ZERO, BigDecimal.ZERO));
                continue;
            }
            PatternDay pd = patternDay(ctx.patterns.get(source.patternId()), d);
            ShiftRow shift = pd == null || pd.shiftId() == null ? null : ctx.shifts.get(pd.shiftId());
            boolean work = shift != null && "WORK".equals(pd.dayKind());
            out.add(new StandardDays.Day(d, employed ? covering.id() : null, work,
                    work ? shift.data().dayValue() : BigDecimal.ZERO,
                    work ? shift.data().stdHours() : BigDecimal.ZERO));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ internals

    private record Context(LocalDate from, List<AssignmentRow> rows, List<LineVersion> lines,
            Map<UUID, PatternRow> patterns, Map<UUID, ShiftRow> shifts, Map<String, List<Holiday>> holidayCache,
            LocalDate holidayFrom, LocalDate holidayTo) {
    }

    private Context load(UUID employmentId, LocalDate from, LocalDate to) {
        List<AssignmentRow> rows = assignments.assignmentsOfEmployment(employmentId, false);
        List<LineVersion> lines = assignments.lineVersionsOfEmployment(employmentId);
        Map<UUID, PatternRow> pats = patterns.patterns(rows.stream().map(AssignmentRow::patternId).distinct().toList());
        Map<UUID, ShiftRow> shifts = patterns.shifts(pats.values().stream().flatMap(p -> p.days().stream())
                .map(PatternDay::shiftId).filter(Objects::nonNull).distinct().toList());
        return new Context(from, rows, lines, pats, shifts, new HashMap<>(), from, to);
    }

    private PlannedDay day(Context ctx, LocalDate d) {
        AssignmentRow a = covering(ctx, d);
        if (a == null) {
            return new PlannedDay(d, false, null, null, null, null, null, null, null, null, null, null, null, null,
                    null);
        }
        PatternRow pattern = ctx.patterns.get(a.patternId());
        PatternDay pd = patternDay(pattern, d);
        ShiftRow shift = pd == null || pd.shiftId() == null ? null : ctx.shifts.get(pd.shiftId());
        LineVersion line = ctx.lines.stream()
                .filter(l -> l.assignmentKey().equals(a.assignmentKey()) && l.covers(d)).findFirst().orElse(null);
        Holiday holiday = null;
        if (line != null) {
            holiday = holidaysOf(ctx, line.legalEntityId(), line.locationId()).stream()
                    .filter(h -> h.date().equals(d) && !h.foreignersOnly() && !"SUBSTITUTE".equals(h.dayType()))
                    .min(Comparator.comparing(h -> "PUBLIC".equals(h.dayType()) ? 0 : 1)).orElse(null);
        }
        return new PlannedDay(d, true, a.id(), a.assignmentKey(), a.patternId(),
                pattern == null ? null : pattern.data().code(), pd == null ? null : pd.dayKind(),
                shift == null ? null : shift.id(), shift == null ? null : shift.data().code(),
                shift == null ? BigDecimal.ZERO : shift.data().stdHours(),
                shift == null ? BigDecimal.ZERO : shift.data().dayValue(),
                line == null ? null : line.legalEntityId(), line == null ? null : line.locationId(), holiday, null);
    }

    private List<Holiday> holidaysOf(Context ctx, UUID legalEntityId, UUID locationId) {
        return ctx.holidayCache.computeIfAbsent(legalEntityId + "|" + locationId,
                k -> holidays.holidays(legalEntityId, locationId, ctx.holidayFrom, ctx.holidayTo, null));
    }

    /** The covering row; several lines: the one that started last (then the higher id, deterministic). */
    private static AssignmentRow covering(Context ctx, LocalDate d) {
        return ctx.rows.stream().filter(a -> a.covers(d))
                .max(Comparator.comparing(AssignmentRow::validFrom).thenComparing(AssignmentRow::id)).orElse(null);
    }

    private static AssignmentRow nearest(Context ctx, LocalDate d) {
        AssignmentRow before = ctx.rows.stream().filter(a -> a.validTo() != null && !a.validTo().isAfter(d))
                .max(Comparator.comparing(AssignmentRow::validTo)).orElse(null);
        if (before != null) {
            return before;
        }
        return ctx.rows.stream().filter(a -> a.validFrom().isAfter(d))
                .min(Comparator.comparing(AssignmentRow::validFrom)).orElse(null);
    }

    static PatternDay patternDay(PatternRow pattern, LocalDate d) {
        if (pattern == null || pattern.days().isEmpty()) {
            return null;
        }
        int len = pattern.data().cycleLengthDays();
        int idx = (int) Math.floorMod(ChronoUnit.DAYS.between(pattern.data().anchorDate(), d), (long) len);
        return pattern.days().stream().filter(x -> x.dayIndex() == idx).findFirst().orElse(null);
    }

    private static PlannedDay withSubstitute(PlannedDay p, LocalDate holiday) {
        return new PlannedDay(p.date(), p.scheduled(), p.scheduleAssignmentId(), p.assignmentKey(), p.patternId(),
                p.patternCode(), p.dayKind(), p.shiftId(), p.shiftCode(), p.stdHours(), p.dayValue(),
                p.legalEntityId(), p.locationId(), p.holiday(), holiday);
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.unprocessable("invalid-range", "Give a date range with from <= to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("range-too-long", "A schedule lookup spans at most " + MAX_RANGE_DAYS
                    + " days");
        }
    }
}
