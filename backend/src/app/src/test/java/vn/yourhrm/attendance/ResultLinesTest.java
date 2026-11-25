package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.domain.period.ResultLines;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.CodeInfo;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.CodeQty;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.DayFacts;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Delta;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Existing;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Line;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Overtime;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Segment;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Standard;

/** The Attendance Result lines of BRD §7.3 and the retro difference of BR-ATT-071 ({@link ResultLines}). */
class ResultLinesTest {

    static final UUID LE = UUID.randomUUID();
    static final UUID KEY_A = UUID.randomUUID();
    static final UUID KEY_B = UUID.randomUUID();
    static final UUID UNIT = UUID.randomUUID();
    static final LocalDate D1 = LocalDate.of(2027, 3, 1);

    static final Map<String, CodeInfo> CODES = Map.of(
            "X", new CodeInfo("WORK", "WORKED_DAYS", null, false),
            "CT", new CodeInfo("TRIP", "WORKED_DAYS", "TRIP", false),
            "P", new CodeInfo("LEAVE", "PAID_LEAVE_DAYS", "ANNUAL", false),
            "KL", new CodeInfo("LEAVE", "UNPAID_DAYS", "PERSONAL", true),
            "NB", new CodeInfo("HOLIDAY", "HOLIDAY_DAYS", "SUBSTITUTE", false));

    static DayFacts day(int offset, UUID key, String code, String qty, int late, String... exceptions) {
        return new DayFacts(D1.plusDays(offset), true, key, LE, UNIT, null, null,
                code == null ? List.of() : List.of(new CodeQty(code, new BigDecimal(qty))), BigDecimal.ZERO, late, 0,
                List.of(exceptions), new BigDecimal(code != null && code.equals("X") ? "8" : "0"), "DAY");
    }

    static Map<String, BigDecimal> totals(List<Line> lines) {
        return lines.stream().collect(Collectors.toMap(Line::category, Line::quantity, BigDecimal::add));
    }

    @Test
    void codesGoToTheirCategoriesAndDayFactsAreCounted() {
        List<DayFacts> days = List.of(day(0, KEY_A, "X", "1", 12), day(1, KEY_A, "CT", "1", 0),
                day(2, KEY_A, "P", "0.5", 0, "MISSING_OUT"), day(3, KEY_A, "KL", "1", 0),
                day(4, KEY_A, "NB", "1", 0));
        Map<String, BigDecimal> t = totals(ResultLines.lines(days, CODES,
                List.of(new Overtime(D1, "OT-WD", false, 90), new Overtime(D1, "OT-WD-N", true, 30)), null));
        assertThat(t).containsEntry("WORKED_DAYS", new BigDecimal("2.00"))
                .containsEntry("TRIP_DAYS", new BigDecimal("1.00"))
                .containsEntry("PAID_LEAVE_DAYS.ANNUAL", new BigDecimal("0.50"))
                .containsEntry("UNPAID_DAYS.PERSONAL", new BigDecimal("1.00"))
                .containsEntry("SI_NONWORK_DAYS", new BigDecimal("1.00"))
                .containsEntry("HOLIDAY_DAYS", new BigDecimal("1.00"))
                .containsEntry("LATE_MIN", new BigDecimal("12.00")).containsEntry("LATE_COUNT", new BigDecimal("1.00"))
                .containsEntry("MISSING_PUNCH_COUNT", new BigDecimal("1.00"))
                .containsEntry("SHIFT_COUNT.DAY", new BigDecimal("1.00"))
                .containsEntry("OT_HOURS.OT-WD", new BigDecimal("1.50"))
                .containsEntry("OT_HOURS.OT-WD-N", new BigDecimal("0.50"))
                .doesNotContainKeys("EARLY_MIN", "NIGHT_HOURS", "STD_DAYS");   // zero lines left out, no method
    }

    @Test
    void aTransferStartsANewSegmentWithItsOwnStandardDays() {
        List<DayFacts> days = List.of(day(0, KEY_A, "X", "1", 0), day(1, KEY_A, "X", "1", 0),
                day(2, KEY_B, "X", "1", 0));
        List<Segment> segs = ResultLines.segments(days);
        assertThat(segs).extracting(Segment::from, Segment::to)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(D1, D1.plusDays(1)),
                        org.assertj.core.groups.Tuple.tuple(D1.plusDays(2), D1.plusDays(2)));
        Standard std = new Standard(Map.of(D1, new BigDecimal[] {new BigDecimal("17.3333"), new BigDecimal("138")},
                D1.plusDays(2), new BigDecimal[] {new BigDecimal("8.6667"), new BigDecimal("70")}),
                new BigDecimal("26"), new BigDecimal("208"));
        List<Line> lines = ResultLines.lines(days, CODES, List.of(), std);
        assertThat(lines).filteredOn(l -> l.category().equals("STD_DAYS")).extracting(Line::quantity)
                .containsExactly(new BigDecimal("17.33"), new BigDecimal("8.67"));
        assertThat(lines).filteredOn(l -> l.category().equals("STD_DAYS"))
                .allSatisfy(l -> assertThat(l.periodQuantity()).isEqualByComparingTo("26"));
        assertThat(lines).filteredOn(l -> l.category().equals("WORKED_DAYS")).extracting(Line::quantity)
                .containsExactly(new BigDecimal("2.00"), new BigDecimal("1.00"));
    }

    @Test
    void theRetroDifferenceSubtractsTheLockedLinesAndEarlierRetroLines() {
        List<DayFacts> fresh = List.of(day(0, KEY_A, "X", "1", 0), day(1, KEY_A, "X", "1", 0));
        List<Line> lines = ResultLines.lines(fresh, CODES, List.of(), null);
        Existing locked = new Existing(D1, D1.plusDays(1), KEY_A, LE, UNIT, null, null, "WORKED_DAYS", "DAY",
                new BigDecimal("1.00"), false);
        Existing lockedLate = new Existing(D1, D1.plusDays(1), KEY_A, LE, UNIT, null, null, "UNPAID_DAYS.ABSENT",
                "DAY", new BigDecimal("1.00"), false);
        Existing lockedShift = new Existing(D1, D1.plusDays(1), KEY_A, LE, UNIT, null, null, "SHIFT_COUNT.DAY",
                "COUNT", new BigDecimal("1.00"), false);
        List<Delta> first = ResultLines.diff(lines, List.of(locked, lockedLate, lockedShift));
        assertThat(first).extracting(Delta::category, Delta::quantity).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("WORKED_DAYS", new BigDecimal("1.00")),
                org.assertj.core.groups.Tuple.tuple("SHIFT_COUNT.DAY", new BigDecimal("1.00")),
                org.assertj.core.groups.Tuple.tuple("UNPAID_DAYS.ABSENT", new BigDecimal("-1.00")));
        // once the deltas were issued, the same fresh days give nothing more
        Existing issued = new Existing(D1, D1.plusDays(1), KEY_A, LE, UNIT, null, null, "RETRO.WORKED_DAYS", "DAY",
                new BigDecimal("1.00"), false);
        Existing issued2 = new Existing(D1, D1.plusDays(1), KEY_A, LE, UNIT, null, null, "RETRO.UNPAID_DAYS.ABSENT",
                "DAY", new BigDecimal("-1.00"), false);
        Existing issued3 = new Existing(D1, D1.plusDays(1), KEY_A, LE, UNIT, null, null, "RETRO.SHIFT_COUNT.DAY",
                "COUNT", new BigDecimal("1.00"), false);
        assertThat(ResultLines.diff(lines, List.of(locked, lockedLate, lockedShift, issued, issued2, issued3)))
                .isEmpty();
    }

    @Test
    void theChecksumIgnoresLineOrder() {
        String a = ResultLines.canonical(KEY_A, D1, D1, "WORKED_DAYS", "DAY", BigDecimal.ONE, null, false, null, null);
        String b = ResultLines.canonical(KEY_B, D1, D1, "WORKED_DAYS", "DAY", BigDecimal.TEN, null, false, null, null);
        assertThat(ResultLines.checksum(List.of(a, b))).isEqualTo(ResultLines.checksum(List.of(b, a)))
                .matches("[0-9a-f]{64}");
    }
}
