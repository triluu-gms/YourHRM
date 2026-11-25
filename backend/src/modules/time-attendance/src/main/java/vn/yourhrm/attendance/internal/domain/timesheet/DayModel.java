package vn.yourhrm.attendance.internal.domain.timesheet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.yourhrm.attendance.internal.domain.schedule.ShiftLayout;

/**
 * Inputs and output of the daily timesheet calculation (BR-ATT-030..035, 039). Every input is explicit, so the same
 * input always gives the same result (BR-ATT-038, NFR-ATT-006); {@link DayInput#toString()} is the canonical form that
 * the inputs hash is taken of.
 */
public final class DayModel {

    private DayModel() {
    }

    /** Rule version stamped on every computed day ({@code daily_timesheet.rule_version}). */
    public static final String RULE_VERSION = "ATT-DAY-1";

    public static final Set<String> PAIRING_MODES = Set.of("FIRST_LAST", "ALL_PAIRS", "BY_DIRECTION");

    /** Exceptions that block the period close (BR-ATT-074). */
    public static final Set<String> BLOCKING = Set.of("MISSING_IN", "MISSING_OUT", "ABSENT_NO_CODE");

    /**
     * The planned shift of a working or non-working day ({@code layout} times are local on the work date). Night
     * window: the shift's override, else the legal pack's (null when neither is known: no night hours).
     */
    public record Shift(UUID id, ShiftLayout layout, int graceInMin, int graceOutMin, String roundingRule,
            int windowBeforeMin, int windowAfterMin, Integer minMinutesHalfDay, Integer minMinutesFullDay,
            BigDecimal stdHours, BigDecimal dayValue, String shiftGroupCode) {
    }

    /** A punch on the timeline; {@code source} RAW (a VALID raw punch) or CORRECTION (an approved correction). */
    public record Punch(UUID id, Instant at, String direction, String source) {
    }

    /** What an attendance code means for the day ({@code code_kind}, {@code counts_as_worked}). */
    public record Code(String code, String kind, boolean countsAsWorked) {
    }

    /** An approved leave of the day: FULL, AM or PM. */
    public record Leave(Code code, String part) {
    }

    /**
     * @param employment     EMPLOYED, NOT_EMPLOYED or SUSPENDED on the date (BR-ATT-039)
     * @param dayType        WORKDAY, REST_DAY, PUBLIC_HOLIDAY, SUBSTITUTE_HOLIDAY, COMPANY_HOLIDAY
     * @param paidHoliday    the holiday (or substitute day) is paid by the employer
     * @param shift          the planned shift, null on a rest day without shift or without schedule
     * @param punches        the punches attached to this work date (VALID raw + corrections), any order
     * @param codeOverride   full-day code of an approved correction / admin edit (e.g. CT, WFH), null when none
     * @param manualOverride an admin edit applies to the day
     * @param protectedMin   statutory paid short break minutes the employee is entitled to that day (BR-ATT-035)
     */
    public record DayInput(LocalDate workDate, ZoneId zone, String employment, String dayType, boolean paidHoliday,
            Shift shift, List<Punch> punches, List<Leave> leaves, Code codeOverride, boolean manualOverride,
            String pairingMode, LocalTime nightStart, LocalTime nightEnd, int protectedMin) {

        public DayInput {
            punches = List.copyOf(punches);
            leaves = List.copyOf(leaves);
        }
    }

    public record CodeQty(String code, BigDecimal qty) {
    }

    /** An interval of real time (OT candidate presence handed to the OT stream). */
    public record TimeSpan(Instant start, Instant end) {
    }

    /** {@code otSpans}: the presence that is OT candidate time (sums to {@code otCandidateMin}). */
    public record DayResult(String dayType, String dayStatus, String primaryCode, List<CodeQty> codes,
            Instant firstInAt, Instant lastOutAt, BigDecimal scheduledHours, BigDecimal workedHours,
            BigDecimal paidBreakHours, int protectedBreakMin, int lateMin, int earlyMin, BigDecimal absentHours,
            BigDecimal nightHours, int otCandidateMin, BigDecimal dayValue, List<String> exceptions,
            boolean blocking, boolean manualOverride, List<TimeSpan> otSpans) {

        public DayResult {
            codes = List.copyOf(codes);
            exceptions = List.copyOf(exceptions);
            otSpans = List.copyOf(otSpans);
        }
    }
}
