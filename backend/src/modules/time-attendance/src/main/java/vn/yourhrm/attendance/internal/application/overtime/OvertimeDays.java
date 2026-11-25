package vn.yourhrm.attendance.internal.application.overtime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.WorkSchedules;
import vn.yourhrm.attendance.api.WorkSchedules.PlannedDay;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.DayType;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Interval;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.ShiftRow;

/**
 * The planned facts of one work date that overtime needs, from the employee's own schedule and holiday calendar
 * ({@link WorkSchedules}): the day type of the work date and of the following days (an overtime minute takes the
 * day type of its own calendar date), the planned shift span, the normal minutes and the local time zone.
 *
 * <p>Day types: a public holiday, a paid company day or the employee's substitute day off → HOLIDAY (OT-PH*); the
 * pattern's weekly rest day → REST_DAY (OT-RD*); any other day, including an OFF day of a 5-day week (not the
 * statutory weekly rest day), → WORKDAY (OT-WD*). An unpaid company day counts as a rest day.
 */
@Component
public class OvertimeDays {

    /** Default when the location's time zone is not known to T&amp;A ({@code att.ext_core_location}). */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * @param shiftStart  planned shift span on a working day (null on a day without shift)
     * @param normalMin   standard minutes of a working day (0 otherwise)
     * @param workingDay  a scheduled working day that is not a holiday or substitute day
     */
    public record DayContext(LocalDate workDate, boolean scheduled, boolean workingDay, int normalMin,
            LocalDateTime shiftStart, LocalDateTime shiftEnd, LocalTime nightStart, LocalTime nightEnd,
            Map<LocalDate, DayType> dayTypes, ZoneId zone) {

        public DayType dayType(LocalDate d) {
            return dayTypes.getOrDefault(d, DayType.WORKDAY);
        }

        public boolean crossesMidnight() {
            return shiftStart != null && shiftEnd.toLocalDate().isAfter(workDate);
        }

        public Interval local(Instant start, Instant end) {
            return new Interval(LocalDateTime.ofInstant(start, zone), LocalDateTime.ofInstant(end, zone));
        }
    }

    private final WorkSchedules schedules;
    private final ShiftPatternStore shifts;
    private final OvertimeStore store;

    public OvertimeDays(WorkSchedules schedules, ShiftPatternStore shifts, OvertimeStore store) {
        this.schedules = schedules;
        this.shifts = shifts;
        this.store = store;
    }

    public DayContext of(UUID employmentId, LocalDate workDate, UUID locationId) {
        List<PlannedDay> plan = schedules.plan(employmentId, workDate, workDate.plusDays(2));
        Map<LocalDate, DayType> types = new HashMap<>();
        PlannedDay day = null;
        for (PlannedDay p : plan) {
            types.put(p.date(), dayType(p));
            if (p.date().equals(workDate)) {
                day = p;
            }
        }
        ZoneId zone = store.timeZone(locationId, workDate).map(ZoneId::of).orElse(DEFAULT_ZONE);
        if (day == null || !day.scheduled()) {
            return new DayContext(workDate, false, false, 0, null, null, null, null, types, zone);
        }
        LocalDateTime start = null;
        LocalDateTime end = null;
        LocalTime nightStart = null;
        LocalTime nightEnd = null;
        if ("WORK".equals(day.dayKind()) && day.shiftId() != null) {
            ShiftRow s = shifts.shift(day.shiftId()).orElse(null);
            if (s != null) {
                start = workDate.atTime(s.data().startTime());
                end = (s.crossesMidnight() ? workDate.plusDays(1) : workDate).atTime(s.data().endTime());
                nightStart = s.data().nightWindowStart();
                nightEnd = s.data().nightWindowEnd();
            }
        }
        BigDecimal std = day.stdHours() == null ? BigDecimal.ZERO : day.stdHours();
        boolean working = day.isWorkingDay();
        int normal = working ? std.multiply(BigDecimal.valueOf(60)).intValue() : 0;
        return new DayContext(workDate, true, working, normal, start, end, nightStart, nightEnd, types, zone);
    }

    static DayType dayType(PlannedDay p) {
        if (p.substituteForDate() != null || (p.holiday() != null && p.holiday().paid())) {
            return DayType.HOLIDAY;
        }
        if (p.holiday() != null || "REST".equals(p.dayKind())) {
            return DayType.REST_DAY;
        }
        return DayType.WORKDAY;
    }
}
