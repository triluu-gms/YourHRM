package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.application.period.ResultCatalogues;
import vn.yourhrm.attendance.internal.domain.period.ResultLines;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.CodeInfo;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.CodeQty;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Dated;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.DayFacts;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Delta;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Existing;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Line;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.MealRule;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Overtime;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Segment;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.SegmentKeys;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Standard;
import vn.yourhrm.core.api.Settings;

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
            "CTNN", new CodeInfo("TRIP", "WORKED_DAYS", "OVERSEAS", false),
            "WFH", new CodeInfo("WFH", "WORKED_DAYS", "WFH", false),
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
                .containsEntry("TRIP_DAYS.DOMESTIC", new BigDecimal("1.00"))
                .containsEntry("PAID_LEAVE_DAYS.ANNUAL", new BigDecimal("0.50"))
                .containsEntry("UNPAID_DAYS.PERSONAL", new BigDecimal("1.00"))
                .containsEntry("SI_NONWORK_DAYS", new BigDecimal("1.00"))
                .containsEntry("HOLIDAY_DAYS", new BigDecimal("1.00"))
                .containsEntry("LATE_MIN", new BigDecimal("12.00")).containsEntry("LATE_COUNT", new BigDecimal("1.00"))
                .containsEntry("MISSING_PUNCH_COUNT", new BigDecimal("1.00"))
                .containsEntry("SHIFT_COUNT.DAY", new BigDecimal("1.00"))
                .containsEntry("OT_HOURS.OT-WD", new BigDecimal("1.50"))
                .containsEntry("OT_HOURS.OT-WD-N", new BigDecimal("0.50"))
                // zero lines left out, no method, no meal rule; trips always carry their destination
                .doesNotContainKeys("EARLY_MIN", "NIGHT_HOURS", "STD_DAYS", "TRIP_DAYS", "MEAL_ELIGIBLE_DAYS");
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

    static DayFacts keyed(int offset, UUID pattern, String group, String fte) {
        DayFacts d = day(offset, KEY_A, "X", "1", 0);
        return new DayFacts(d.date(), true, KEY_A, LE, UNIT, null, null, d.codes(), BigDecimal.ZERO, 0, 0, List.of(),
                d.workedHours(), "DAY", new SegmentKeys(pattern, group, fte == null ? null : new BigDecimal(fte)));
    }

    static DayFacts worked(int offset, String code, String hours) {
        return new DayFacts(D1.plusDays(offset), true, KEY_A, LE, UNIT, null, null,
                List.of(new CodeQty(code, BigDecimal.ONE)), BigDecimal.ZERO, 0, 0, List.of(), new BigDecimal(hours),
                null);
    }

    @Test
    void aScheduleEmployeeGroupOrFteChangeStartsANewSegment() {
        UUID weekly5 = UUID.randomUUID();
        UUID weekly6 = UUID.randomUUID();
        List<DayFacts> days = List.of(
                keyed(0, weekly5, "STAFF", "1"), keyed(1, weekly5, "STAFF", "1.0000"),   // same FTE by value
                keyed(2, weekly6, "STAFF", "1"),                                          // schedule changed
                keyed(3, weekly6, "WORKER", "1"),                                         // employee group changed
                keyed(4, weekly6, "WORKER", "0.5"));                                      // FTE changed
        List<Segment> segs = ResultLines.segments(days);
        assertThat(segs).extracting(Segment::from).containsExactly(D1, D1.plusDays(2), D1.plusDays(3),
                D1.plusDays(4));
        assertThat(segs.getFirst().keys()).isEqualTo(new SegmentKeys(weekly5, "STAFF", new BigDecimal("1.00")));
        assertThat(segs.getLast().keys().fte()).isEqualByComparingTo("0.5");
        List<Line> lines = ResultLines.lines(days, CODES, List.of(), null);
        assertThat(lines).filteredOn(l -> l.category().equals("WORKED_DAYS"))
                .extracting(l -> l.segment().from(), Line::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(D1, new BigDecimal("2.00")),
                        org.assertj.core.groups.Tuple.tuple(D1.plusDays(2), new BigDecimal("1.00")),
                        org.assertj.core.groups.Tuple.tuple(D1.plusDays(3), new BigDecimal("1.00")),
                        org.assertj.core.groups.Tuple.tuple(D1.plusDays(4), new BigDecimal("1.00")));
    }

    @Test
    void theRetroDifferenceIsKeyedByTheNewSegmentsAndKeepsTheirKeys() {
        UUID weekly5 = UUID.randomUUID();
        UUID weekly6 = UUID.randomUUID();
        SegmentKeys k5 = new SegmentKeys(weekly5, "STAFF", BigDecimal.ONE);
        SegmentKeys k6 = new SegmentKeys(weekly6, "STAFF", BigDecimal.ONE);
        List<DayFacts> fresh = List.of(keyed(0, weekly5, "STAFF", "1"), keyed(1, weekly6, "STAFF", "1"));
        List<Line> lines = ResultLines.lines(fresh, CODES, List.of(), null);
        // locked: the same split, each segment one worked day, keys read back from the stored line
        List<Existing> locked = List.of(
                new Existing(D1, D1, KEY_A, LE, UNIT, null, null, "WORKED_DAYS", "DAY", BigDecimal.ONE, false, k5),
                new Existing(D1, D1, KEY_A, LE, UNIT, null, null, "SHIFT_COUNT.DAY", "COUNT", BigDecimal.ONE, false,
                        k5),
                new Existing(D1.plusDays(1), D1.plusDays(1), KEY_A, LE, UNIT, null, null, "WORKED_DAYS", "DAY",
                        BigDecimal.ONE, false, k6));
        List<Delta> deltas = ResultLines.diff(lines, locked);
        assertThat(deltas).singleElement().satisfies(d -> {
            assertThat(d.category()).isEqualTo("SHIFT_COUNT.DAY");
            assertThat(d.segment().from()).isEqualTo(D1.plusDays(1));
            assertThat(d.segment().keys()).isEqualTo(k6);
        });
        // a locked line of a segment that no longer exists is reversed on its own segment, with its stored keys
        List<Delta> reversed = ResultLines.diff(List.of(), List.of(locked.getFirst()));
        assertThat(reversed).singleElement().satisfies(d -> {
            assertThat(d.quantity()).isEqualByComparingTo("-1");
            assertThat(d.segment().keys()).isEqualTo(k5);
        });
    }

    @Test
    void tripDaysAreSplitByDestinationAndStillCountAsWorked() {
        List<DayFacts> days = List.of(day(0, KEY_A, "CT", "1", 0), day(1, KEY_A, "CTNN", "1", 0),
                day(2, KEY_A, "CTNN", "0.5", 0));
        Map<String, BigDecimal> t = totals(ResultLines.lines(days, CODES, List.of(), null));
        assertThat(t).containsEntry("TRIP_DAYS.DOMESTIC", new BigDecimal("1.00"))
                .containsEntry("TRIP_DAYS.OVERSEAS", new BigDecimal("1.50"))
                .containsEntry("WORKED_DAYS", new BigDecimal("2.50"));
        assertThat(ResultLines.tripDestination(new CodeInfo("TRIP", "WORKED_DAYS", null, false)))
                .isEqualTo("DOMESTIC");
        assertThat(ResultLines.tripDestination(new CodeInfo("TRIP", "WORKED_DAYS", "overseas", false)))
                .isEqualTo("OVERSEAS");
    }

    @Test
    void mealEligibleDaysFollowTheTenantRule() {
        List<DayFacts> days = List.of(worked(0, "X", "8"), worked(1, "X", "4"), worked(2, "X", "3.5"),
                worked(3, "CT", "0"), worked(4, "WFH", "0"), worked(5, "P", "0"));
        MealRule all = new MealRule(new BigDecimal("4"), true, true);
        assertThat(totals(ResultLines.lines(days, CODES, List.of(), null, all, List.of())))
                .containsEntry("MEAL_ELIGIBLE_DAYS", new BigDecimal("4.00"));   // 8h, 4h, trip, WFH
        MealRule officeOnly = new MealRule(new BigDecimal("6"), false, false);
        assertThat(totals(ResultLines.lines(days, CODES, List.of(), null, officeOnly, List.of())))
                .containsEntry("MEAL_ELIGIBLE_DAYS", new BigDecimal("1.00"));   // 8h only
        MealRule none = new MealRule(new BigDecimal("12"), false, false);
        assertThat(totals(ResultLines.lines(days, CODES, List.of(), null, none, List.of())))
                .doesNotContainKey("MEAL_ELIGIBLE_DAYS");
        // per segment: a transfer splits the eligible days too
        List<DayFacts> split = List.of(worked(0, "X", "8"), day(1, KEY_B, "X", "1", 0));
        assertThat(ResultLines.lines(split, CODES, List.of(), null, all, List.of()))
                .filteredOn(l -> l.category().equals("MEAL_ELIGIBLE_DAYS")).extracting(Line::quantity)
                .containsExactly(new BigDecimal("1.00"), new BigDecimal("1.00"));
    }

    @Test
    void paidOutLeaveGoesToTheEmployedSegmentOfItsLedgerDate() {
        DayFacts notEmployed = new DayFacts(D1.plusDays(2), false, null, LE, null, null, null, List.of(),
                BigDecimal.ZERO, 0, 0, List.of(), BigDecimal.ZERO, null);
        List<DayFacts> days = List.of(day(0, KEY_A, "X", "1", 0), day(1, KEY_B, "X", "1", 0), notEmployed);
        List<Line> lines = ResultLines.lines(days, CODES, List.of(), null, null, List.of(
                new Dated(D1, "LEAVE_ENCASH_DAYS", "DAY", new BigDecimal("1.5")),
                new Dated(D1.plusDays(2), "LEAVE_ENCASH_DAYS", "DAY", new BigDecimal("2")),
                new Dated(D1, "LEAVE_ENCASH_DAYS", "DAY", BigDecimal.ZERO)));
        assertThat(lines).filteredOn(l -> l.category().equals("LEAVE_ENCASH_DAYS"))
                .extracting(l -> l.segment().from(), Line::quantity, Line::unit)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(D1, new BigDecimal("1.50"), "DAY"),
                        org.assertj.core.groups.Tuple.tuple(D1.plusDays(1), new BigDecimal("2.00"), "DAY"));
    }

    @Test
    void theMealRuleComesFromTheLegalEntitySettings() {
        Map<String, Object> values = new java.util.HashMap<>(Map.of(ResultCatalogues.SETTING_MEAL_MIN_HOURS, 6.5,
                ResultCatalogues.SETTING_MEAL_TRIP_DAYS, false, ResultCatalogues.SETTING_MEAL_WFH_DAYS, true));
        Settings settings = new Settings() {
            @Override
            public Object value(String key, Scope scope, LocalDate asOf) {
                assertThat(scope.legalEntityId()).isEqualTo(LE);
                return values.get(key);
            }

            @Override
            public boolean booleanValue(String key, Scope scope, LocalDate asOf) {
                return (Boolean) value(key, scope, asOf);
            }

            @Override
            public long longValue(String key, Scope scope, LocalDate asOf) {
                return ((Number) value(key, scope, asOf)).longValue();
            }

            @Override
            public String stringValue(String key, Scope scope, LocalDate asOf) {
                return String.valueOf(value(key, scope, asOf));
            }
        };
        assertThat(ResultCatalogues.mealRule(settings, LE, D1))
                .isEqualTo(new MealRule(new BigDecimal("6.5"), false, true));
        values.put(ResultCatalogues.SETTING_MEAL_MIN_HOURS, -1);
        assertThat(ResultCatalogues.mealRule(settings, LE, D1).minWorkedHours()).isZero();
        assertThat(new ResultCatalogues().settings()).extracting(s -> s.key()).containsExactly(
                "att.meal_min_worked_hours", "att.meal_trip_days_eligible", "att.meal_wfh_days_eligible");
    }

    @Test
    void theChecksumIgnoresLineOrder() {
        String a = ResultLines.canonical(KEY_A, D1, D1, "WORKED_DAYS", "DAY", BigDecimal.ONE, null, false, null, null);
        String b = ResultLines.canonical(KEY_B, D1, D1, "WORKED_DAYS", "DAY", BigDecimal.TEN, null, false, null, null);
        assertThat(ResultLines.checksum(List.of(a, b))).isEqualTo(ResultLines.checksum(List.of(b, a)))
                .matches("[0-9a-f]{64}");
    }
}
