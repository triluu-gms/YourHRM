package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.domain.schedule.ShiftLayout;
import vn.yourhrm.attendance.internal.domain.timesheet.DayCalculator;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Code;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.CodeQty;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.DayInput;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.DayResult;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Leave;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Punch;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Shift;
import vn.yourhrm.attendance.internal.domain.timesheet.PunchAttachment;

/** The daily calculation matrix (BR-ATT-030..035, 039; US-ATT-023..029). */
class DayCalculatorTest {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final LocalDate D = LocalDate.of(2026, 10, 12); // a Monday

    /** 08:00-17:00, unpaid lunch 12:00-13:00, grace 5 / 5, 8h, value 1. */
    static Shift office(String rounding) {
        return new Shift(UUID.randomUUID(), new ShiftLayout(LocalTime.of(8, 0), LocalTime.of(17, 0),
                List.of(new ShiftLayout.Break(LocalTime.of(12, 0), LocalTime.of(13, 0), 60, false, false))), 5, 5,
                rounding, 120, 240, null, null, new BigDecimal("8"), BigDecimal.ONE, null);
    }

    static Shift night() {
        return new Shift(UUID.randomUUID(), new ShiftLayout(LocalTime.of(22, 0), LocalTime.of(6, 0), List.of()), 0,
                0, null, 120, 240, null, null, new BigDecimal("8"), BigDecimal.ONE, "NIGHT");
    }

    static Punch at(LocalDate d, String hhmm, String dir) {
        return new Punch(UUID.randomUUID(), LocalDateTime.of(d, LocalTime.parse(hhmm)).atZone(VN).toInstant(), dir,
                "RAW");
    }

    static DayInput input(String dayType, Shift shift, List<Punch> punches, List<Leave> leaves, String mode,
            int protectedMin) {
        return new DayInput(D, VN, "EMPLOYED", dayType, true, shift, punches, leaves, null, false, mode,
                LocalTime.of(22, 0), LocalTime.of(6, 0), protectedMin);
    }

    static DayResult work(Punch... punches) {
        return DayCalculator.compute(input("WORKDAY", office(null), List.of(punches), List.of(), "FIRST_LAST", 0));
    }

    static List<String> codes(DayResult r) {
        return r.codes().stream().map(c -> c.code() + ":" + c.qty().stripTrailingZeros().toPlainString()).toList();
    }

    @Test
    void aFullDayWithinGraceIsPresentAndTheTimeOutsideTheShiftIsAnOtCandidate() {
        DayResult r = work(at(D, "07:55", "IN"), at(D, "17:02", "OUT"));
        assertThat(r.dayStatus()).isEqualTo("PRESENT");
        assertThat(codes(r)).containsExactly("X:1");
        assertThat(r.workedHours()).isEqualByComparingTo("8.00");
        assertThat(r.lateMin()).isZero();
        assertThat(r.earlyMin()).isZero();
        assertThat(r.otCandidateMin()).isEqualTo(7);
        assertThat(r.otSpans()).hasSize(2);
        assertThat(r.absentHours()).isEqualByComparingTo("0");
        assertThat(r.exceptions()).isEmpty();
    }

    @Test
    void lateArrivalBelowTheFullDayMinimumGivesHalfADayAndALateException() {
        DayResult r = work(at(D, "08:20", "IN"), at(D, "17:00", "OUT"));
        assertThat(r.lateMin()).isEqualTo(20);
        assertThat(r.workedHours()).isEqualByComparingTo("7.67");
        assertThat(codes(r)).containsExactly("X:0.5", "V:0.5");
        assertThat(r.dayStatus()).isEqualTo("PARTIAL");
        assertThat(r.exceptions()).containsExactly("LATE");
        assertThat(r.blocking()).isFalse();
    }

    @Test
    void aSinglePunchIsIncompleteAndNoPunchIsAnAbsence() {
        DayResult one = work(at(D, "08:00", "IN"));
        assertThat(one.dayStatus()).isEqualTo("INCOMPLETE");
        assertThat(one.exceptions()).containsExactly("MISSING_OUT");
        assertThat(one.blocking()).isTrue();
        DayResult none = work();
        assertThat(none.dayStatus()).isEqualTo("ABSENT");
        assertThat(codes(none)).containsExactly("V:1");
        assertThat(none.absentHours()).isEqualByComparingTo("8.00");
        assertThat(none.exceptions()).containsExactly("ABSENT_NO_CODE");
        assertThat(none.blocking()).isTrue();
    }

    @Test
    void pairingModesReadThePunchesDifferently() {
        List<Punch> four = List.of(at(D, "08:00", "IN"), at(D, "11:00", "OUT"), at(D, "13:00", "IN"),
                at(D, "17:00", "OUT"));
        DayResult firstLast = DayCalculator.compute(input("WORKDAY", office(null), four, List.of(), "FIRST_LAST", 0));
        DayResult allPairs = DayCalculator.compute(input("WORKDAY", office(null), four, List.of(), "ALL_PAIRS", 0));
        assertThat(firstLast.workedHours()).isEqualByComparingTo("8.00");
        assertThat(allPairs.workedHours()).isEqualByComparingTo("7.00"); // 11:00-12:00 not present
        List<Punch> twoIns = List.of(at(D, "08:00", "IN"), at(D, "08:05", "IN"), at(D, "17:00", "OUT"));
        DayResult byDirection = DayCalculator.compute(input("WORKDAY", office(null), twoIns, List.of(),
                "BY_DIRECTION", 0));
        assertThat(byDirection.exceptions()).contains("MISSING_OUT");
    }

    @Test
    void restDaysAndHolidaysCarryNoNormalWorkAndOnlyOtCandidates() {
        DayResult rest = DayCalculator.compute(input("REST_DAY", null, List.of(at(D, "09:00", "IN"),
                at(D, "11:00", "OUT")), List.of(), "FIRST_LAST", 0));
        assertThat(rest.dayStatus()).isEqualTo("REST");
        assertThat(rest.codes()).isEmpty();
        assertThat(rest.otCandidateMin()).isEqualTo(120);
        DayResult holiday = DayCalculator.compute(input("PUBLIC_HOLIDAY", office(null), List.of(), List.of(),
                "FIRST_LAST", 0));
        assertThat(holiday.dayStatus()).isEqualTo("HOLIDAY");
        assertThat(codes(holiday)).containsExactly("L:1");
        DayResult substitute = DayCalculator.compute(input("SUBSTITUTE_HOLIDAY", office(null), List.of(), List.of(),
                "FIRST_LAST", 0));
        assertThat(codes(substitute)).containsExactly("NB:1");
    }

    @Test
    void aHalfDayLeaveAndAHalfDayOfWorkMakeAFullDayOfTwoCodes() {
        Code p = new Code("P", "LEAVE", false);
        DayResult r = DayCalculator.compute(input("WORKDAY", office(null), List.of(at(D, "13:00", "IN"),
                at(D, "17:00", "OUT")), List.of(new Leave(p, "AM")), "FIRST_LAST", 0));
        assertThat(codes(r)).containsExactlyInAnyOrder("P:0.5", "X:0.5");
        assertThat(r.dayStatus()).isEqualTo("PRESENT");
        DayResult full = DayCalculator.compute(input("WORKDAY", office(null), List.of(), List.of(new Leave(p, "FULL")),
                "FIRST_LAST", 0));
        assertThat(full.dayStatus()).isEqualTo("ON_LEAVE");
        assertThat(codes(full)).containsExactly("P:1");
        assertThat(full.exceptions()).isEmpty();
    }

    @Test
    void aNightShiftBelongsToItsStartDateAndItsNormalWorkIsNightWork() {
        Shift n = night();
        Punch in = at(D, "21:55", "IN");
        Punch out = at(D.plusDays(1), "06:03", "OUT");
        var attached = PunchAttachment.attach(List.of(in, out), Map.of(D, n, D.plusDays(1), n), VN);
        assertThat(attached.byDate().get(D)).containsExactly(in, out);
        DayResult r = DayCalculator.compute(input("WORKDAY", n, attached.byDate().get(D), List.of(), "FIRST_LAST", 0));
        assertThat(r.workedHours()).isEqualByComparingTo("8.00");
        assertThat(r.nightHours()).isEqualByComparingTo("8.00");
        assertThat(r.otCandidateMin()).isEqualTo(8);
        // a punch on a rest day belongs to its own date
        Punch sat = at(D.plusDays(5), "10:00", "IN");
        assertThat(PunchAttachment.attach(List.of(sat), Map.of(D, n), VN).byDate()).containsKey(D.plusDays(5));
    }

    @Test
    void statutoryShortBreaksAreCreditedAsWorkingTime() {
        DayResult r = DayCalculator.compute(input("WORKDAY", office(null), List.of(at(D, "08:00", "IN"),
                at(D, "16:00", "OUT")), List.of(), "FIRST_LAST", 60));
        assertThat(r.protectedBreakMin()).isEqualTo(60);
        assertThat(r.earlyMin()).isZero();
        assertThat(r.workedHours()).isEqualByComparingTo("8.00");
        assertThat(codes(r)).containsExactly("X:1");
        assertThat(r.exceptions()).isEmpty();
    }

    @Test
    void roundingNotEmployedAndOverridesFollowTheirRules() {
        DayResult rounded = DayCalculator.compute(input("WORKDAY", office("ROUND_DOWN_15"),
                List.of(at(D, "08:00", "IN"), at(D, "16:50", "OUT")), List.of(), "FIRST_LAST", 0));
        assertThat(rounded.workedHours()).isEqualByComparingTo("7.75");
        DayResult ne = DayCalculator.compute(new DayInput(D, VN, "NOT_EMPLOYED", "WORKDAY", false, office(null),
                List.of(at(D, "08:00", "IN")), List.of(), null, false, "FIRST_LAST", null, null, 0));
        assertThat(ne.dayStatus()).isEqualTo("NOT_EMPLOYED");
        assertThat(ne.codes()).containsExactly(new CodeQty("NE", BigDecimal.ONE));
        DayResult trip = DayCalculator.compute(new DayInput(D, VN, "EMPLOYED", "WORKDAY", false, office(null),
                List.of(), List.of(), new Code("CT", "TRIP", true), true, "FIRST_LAST", null, null, 0));
        assertThat(trip.dayStatus()).isEqualTo("PRESENT");
        assertThat(codes(trip)).containsExactly("CT:1");
        assertThat(trip.dayValue()).isEqualByComparingTo("1");
        assertThat(trip.manualOverride()).isTrue();
    }

    @Test
    void aTripOrWfhCodeOnARestDayOrHolidayIsNotApplied() {
        Code ct = new Code("CT", "TRIP", true);
        DayResult holiday = DayCalculator.compute(new DayInput(D, VN, "EMPLOYED", "PUBLIC_HOLIDAY", true,
                office(null), List.of(), List.of(), ct, true, "FIRST_LAST", null, null, 0));
        assertThat(holiday.dayStatus()).isEqualTo("HOLIDAY");
        assertThat(codes(holiday)).containsExactly("L:1");
        assertThat(holiday.exceptions()).containsExactly(DayCalculator.CODE_NOT_APPLIED);
        assertThat(holiday.blocking()).isFalse();

        DayResult rest = DayCalculator.compute(new DayInput(D, VN, "EMPLOYED", "REST_DAY", false, null,
                List.of(at(D, "08:00", "IN"), at(D, "12:00", "OUT")), List.of(), new Code("WFH", "WFH", true), true,
                "FIRST_LAST", null, null, 0));
        assertThat(rest.dayStatus()).isEqualTo("REST");
        assertThat(rest.codes()).isEmpty();
        assertThat(rest.dayValue()).isEqualByComparingTo("0");
        assertThat(rest.otCandidateMin()).isEqualTo(240);
        assertThat(rest.exceptions()).containsExactly(DayCalculator.CODE_NOT_APPLIED);
    }

    @Test
    void theSameInputGivesTheSameResult() {
        List<Punch> p = new ArrayList<>(List.of(at(D, "08:03", "IN"), at(D, "17:00", "OUT")));
        DayInput a = input("WORKDAY", office(null), p, List.of(), "FIRST_LAST", 0);
        DayInput b = input("WORKDAY", office(null), List.of(p.get(1), p.get(0)), List.of(), "FIRST_LAST", 0);
        assertThat(DayCalculator.compute(a)).isEqualTo(DayCalculator.compute(b));
        assertThat(Instant.EPOCH).isNotNull();
    }
}
