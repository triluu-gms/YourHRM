package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.domain.schedule.DateRange;
import vn.yourhrm.attendance.internal.domain.schedule.RuleSegments;
import vn.yourhrm.attendance.internal.domain.schedule.RuleSegments.Rule;
import vn.yourhrm.attendance.internal.domain.schedule.RuleSegments.Segment;
import vn.yourhrm.attendance.internal.domain.schedule.RuleSegments.Version;
import vn.yourhrm.attendance.internal.domain.schedule.ShiftLayout;
import vn.yourhrm.attendance.internal.domain.schedule.ShiftLayout.Break;
import vn.yourhrm.attendance.internal.domain.schedule.StandardDays;
import vn.yourhrm.attendance.internal.domain.schedule.StandardDays.Day;

/** BR-ATT-010 shift layout, BR-ATT-015 rule segments, BR-ATT-016 standard days. */
class ScheduleDomainTest {

    static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    static LocalTime t(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    @Nested
    class Shifts {

        @Test
        void officeShiftWithAnUnpaidLunchBreak() {
            ShiftLayout s = new ShiftLayout(t("08:00"), t("17:00"),
                    List.of(new Break(t("12:00"), t("13:00"), 60, false, false)));
            assertThat(s.crossesMidnight()).isFalse();
            assertThat(s.spanMinutes()).isEqualTo(540);
            assertThat(s.workingMinutes()).isEqualTo(480);
            assertThat(s.longestContinuousMinutes()).isEqualTo(240);
            assertThat(s.breakProblems()).isEmpty();
        }

        @Test
        void overnightShiftPlacesBreaksAfterMidnight() {
            ShiftLayout s = new ShiftLayout(t("22:00"), t("06:00"),
                    List.of(new Break(t("02:00"), t("02:45"), 45, true, false)));
            assertThat(s.crossesMidnight()).isTrue();
            assertThat(s.spanMinutes()).isEqualTo(480);
            assertThat(s.workingMinutes()).isEqualTo(480);
            assertThat(s.breakProblems()).isEmpty();
        }

        @Test
        void equalStartAndEndIsA24HourShift() {
            assertThat(new ShiftLayout(t("07:00"), t("07:00"), List.of()).spanMinutes()).isEqualTo(1440);
        }

        @Test
        void inconsistentBreaksAreReported() {
            assertThat(new ShiftLayout(t("22:00"), t("06:00"),
                    List.of(new Break(t("07:00"), t("07:30"), 30, false, false))).breakProblems())
                    .contains("break-outside-shift");
            assertThat(new ShiftLayout(t("08:00"), t("17:00"),
                    List.of(new Break(t("12:00"), t("13:00"), 30, false, false))).breakProblems())
                    .contains("break-duration-mismatch");
            assertThat(new ShiftLayout(t("08:00"), t("17:00"), List.of(
                    new Break(t("12:00"), t("13:00"), 60, false, false),
                    new Break(t("12:30"), t("13:30"), 60, false, false))).breakProblems())
                    .contains("breaks-overlap");
            assertThat(new ShiftLayout(t("08:00"), t("17:00"),
                    List.of(new Break(null, null, 30, false, true))).breakProblems()).isEmpty();
        }
    }

    @Nested
    class Rules {

        final UUID le = UUID.randomUUID();
        final UUID hcm = UUID.randomUUID();
        final UUID p5 = UUID.randomUUID();
        final UUID p6 = UUID.randomUUID();
        final Rule siteRule = new Rule(UUID.randomUUID(), "SITE", 10, le, null, hcm, null, null, null, p6,
                new DateRange(d("2027-01-01"), null));
        final Rule entityRule = new Rule(UUID.randomUUID(), "LE", 20, le, null, null, null, null, null, p5,
                new DateRange(d("2027-01-01"), null));

        Version version(String from, String to, UUID location) {
            return new Version(new DateRange(d(from), to == null ? null : d(to)), le, UUID.randomUUID(), location,
                    null, null, null);
        }

        @Test
        void firstMatchingRuleByPriorityWins() {
            List<Segment> s = RuleSegments.generate(List.of(version("2027-01-01", null, hcm)),
                    List.of(entityRule, siteRule), List.of());
            assertThat(s).containsExactly(new Segment(siteRule.id(), p6, new DateRange(d("2027-01-01"), null)));
        }

        @Test
        void aLocationChangeSwitchesThePatternAndSameRuleVersionsMerge() {
            List<Segment> s = RuleSegments.generate(List.of(
                    version("2027-01-01", "2027-02-01", hcm),
                    version("2027-02-01", "2027-03-01", hcm),
                    version("2027-03-01", null, null)), List.of(siteRule, entityRule), List.of());
            assertThat(s).containsExactly(
                    new Segment(siteRule.id(), p6, new DateRange(d("2027-01-01"), d("2027-03-01"))),
                    new Segment(entityRule.id(), p5, new DateRange(d("2027-03-01"), null)));
        }

        @Test
        void ruleValidityAndManualRowsCutSegments() {
            Rule ending = new Rule(siteRule.id(), "SITE", 10, le, null, hcm, null, null, null, p6,
                    new DateRange(d("2027-01-01"), d("2027-06-01")));
            List<Segment> s = RuleSegments.generate(List.of(version("2027-01-01", null, hcm)),
                    List.of(ending, entityRule), List.of(new DateRange(d("2027-02-01"), d("2027-03-01"))));
            assertThat(s).containsExactly(
                    new Segment(ending.id(), p6, new DateRange(d("2027-01-01"), d("2027-02-01"))),
                    new Segment(ending.id(), p6, new DateRange(d("2027-03-01"), d("2027-06-01"))),
                    new Segment(entityRule.id(), p5, new DateRange(d("2027-06-01"), null)));
        }

        @Test
        void noMatchingRuleLeavesAGap() {
            Rule otherEntity = new Rule(UUID.randomUUID(), "OTHER", 1, UUID.randomUUID(), null, null, null, null,
                    null, p5, new DateRange(d("2027-01-01"), null));
            assertThat(RuleSegments.generate(List.of(version("2027-01-01", null, null)), List.of(otherEntity),
                    List.of())).isEmpty();
        }

        @Test
        void categoryAndRegimeCriteriaMatchExactly() {
            Rule partTime = new Rule(UUID.randomUUID(), "PT", 5, null, null, null, null, null, "PART_TIME", p5,
                    new DateRange(d("2027-01-01"), null));
            Version fullTime = new Version(new DateRange(d("2027-01-01"), null), le, null, null, null, "STAFF",
                    "FULL_TIME");
            assertThat(RuleSegments.generate(List.of(fullTime), List.of(partTime), List.of())).isEmpty();
        }
    }

    @Nested
    class Standard {

        final UUID seg = UUID.randomUUID();

        /** May 2027, Mon-Fri 8h; employed from Monday 17 May. */
        List<Day> may() {
            List<Day> out = new ArrayList<>();
            for (LocalDate x = d("2027-05-01"); !x.isAfter(d("2027-05-31")); x = x.plusDays(1)) {
                boolean work = x.getDayOfWeek().getValue() <= 5;
                out.add(new Day(x, x.getDayOfMonth() >= 17 ? seg : null, work,
                        work ? BigDecimal.ONE : BigDecimal.ZERO, work ? BigDecimal.valueOf(8) : BigDecimal.ZERO));
            }
            return out;
        }

        @Test
        void scheduleDays() {
            StandardDays.Result r = StandardDays.compute("SCHEDULE_DAYS", null, may());
            assertThat(r.periodStdDays()).isEqualByComparingTo("21");
            assertThat(r.periodStdHours()).isEqualByComparingTo("168");
            assertThat(r.segments()).singleElement().satisfies(s -> {
                assertThat(s.stdDays()).isEqualByComparingTo("11");
                assertThat(s.stdHours()).isEqualByComparingTo("88");
                assertThat(s.from()).isEqualTo(d("2027-05-17"));
                assertThat(s.to()).isEqualTo(d("2027-05-31"));
            });
        }

        @Test
        void calendarDays() {
            StandardDays.Result r = StandardDays.compute("CALENDAR_DAYS", null, may());
            assertThat(r.periodStdDays()).isEqualByComparingTo("31");
            assertThat(r.segments().getFirst().stdDays()).isEqualByComparingTo("15");
        }

        /** D1: the segment (17-31 May) has 15 of the period's 31 calendar days, whatever its schedule. */
        @Test
        void fixedDaysAndFixedHoursAreSharedByCalendarDays() {
            StandardDays.Result days = StandardDays.compute("FIXED_DAYS", BigDecimal.valueOf(26), may());
            assertThat(days.periodStdDays()).isEqualByComparingTo("26");
            assertThat(days.segments().getFirst().stdDays()).isEqualByComparingTo("12.5806");
            assertThat(days.segments().getFirst().stdHours()).isEqualByComparingTo("88");
            StandardDays.Result hours = StandardDays.compute("FIXED_HOURS", BigDecimal.valueOf(160), may());
            assertThat(hours.periodStdHours()).isEqualByComparingTo("160");
            assertThat(hours.segments().getFirst().stdHours()).isEqualByComparingTo("77.4194");
            assertThat(hours.segments().getFirst().stdDays()).isEqualByComparingTo("11");
        }

        /** D1 worked example: 1-30 Nov, transfer on 13 Nov, FIXED_DAYS 26 -> 26x12/30 and 26x18/30. */
        @Test
        void fixedDaysTransferSplitsByCalendarDays() {
            UUID before = UUID.randomUUID();
            UUID after = UUID.randomUUID();
            List<Day> nov = new ArrayList<>();
            for (LocalDate x = d("2027-11-01"); !x.isAfter(d("2027-11-30")); x = x.plusDays(1)) {
                boolean work = x.getDayOfWeek().getValue() <= 5;
                nov.add(new Day(x, x.getDayOfMonth() < 13 ? before : after, work,
                        work ? BigDecimal.ONE : BigDecimal.ZERO, work ? BigDecimal.valueOf(8) : BigDecimal.ZERO));
            }
            StandardDays.Result r = StandardDays.compute("FIXED_DAYS", BigDecimal.valueOf(26), nov);
            assertThat(r.periodStdDays()).isEqualByComparingTo("26");
            assertThat(r.segments()).extracting(StandardDays.SegmentValue::stdDays)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(new BigDecimal("10.4"), new BigDecimal("15.6"));
        }

        @Test
        void fixedMethodsNeedAValue() {
            assertThatThrownBy(() -> StandardDays.compute("FIXED_DAYS", null, may()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
