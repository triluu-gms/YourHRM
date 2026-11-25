package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Bucket;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Category;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.DayType;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Interval;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.NightWindow;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.Level;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.ProtectedOutcome;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.ProtectedRule;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.ResultRow;

/** BR-ATT-041/042 categories, BR-ATT-044 paid minutes, BR-ATT-045/046 caps, BR-ATT-048 protected workers. */
class OvertimeDomainTest {

    static final NightWindow NIGHT = new NightWindow(LocalTime.of(22, 0), LocalTime.of(6, 0));

    static Interval iv(String from, String to) {
        return new Interval(LocalDateTime.parse(from), LocalDateTime.parse(to));
    }

    static Map<Category, Bucket> byCategory(List<Bucket> buckets) {
        return buckets.stream().collect(java.util.stream.Collectors.toMap(Bucket::category, b -> b));
    }

    @Nested
    class Categories {

        /** Thursday 2 Sep 2027 is a holiday; Wed 1 Sep a working day; Sunday 5 Sep the weekly rest day. */
        final Function<LocalDate, DayType> days = d -> switch (d.toString()) {
            case "2027-09-02" -> DayType.HOLIDAY;
            case "2027-09-05" -> DayType.REST_DAY;
            default -> DayType.WORKDAY;
        };

        @Test
        void eveningOvertimeOnAWorkingDay() {
            assertThat(OvertimeClassifier.classify(List.of(iv("2027-09-01T17:00", "2027-09-01T20:00")), days, NIGHT))
                    .containsExactly(new Bucket(Category.OT_WD, 180, false));
        }

        @Test
        void nightOvertimeAfterDayOvertimeIsFlagged() {
            Map<Category, Bucket> b = byCategory(OvertimeClassifier.classify(
                    List.of(iv("2027-09-01T20:00", "2027-09-01T23:30")), days, NIGHT));
            assertThat(b.get(Category.OT_WD)).isEqualTo(new Bucket(Category.OT_WD, 120, false));
            assertThat(b.get(Category.OT_WD_N)).isEqualTo(new Bucket(Category.OT_WD_N, 90, true));
        }

        /** Each minute takes the day type of its own date: after midnight the holiday's night category applies. */
        @Test
        void nightOvertimeCrossingIntoAHolidaySplitsAtMidnight() {
            Map<Category, Bucket> b = byCategory(OvertimeClassifier.classify(
                    List.of(iv("2027-09-01T22:00", "2027-09-02T02:00")), days, NIGHT));
            assertThat(b).containsOnlyKeys(Category.OT_WD_N, Category.OT_PH_N);
            assertThat(b.get(Category.OT_WD_N).minutes()).isEqualTo(120);
            assertThat(b.get(Category.OT_PH_N).minutes()).isEqualTo(120);
            assertThat(b.get(Category.OT_PH_N).precededByDayOt()).isFalse();
        }

        @Test
        void restDayAndHolidayDayWork() {
            assertThat(OvertimeClassifier.classify(List.of(iv("2027-09-05T08:00", "2027-09-05T12:00")), days, NIGHT))
                    .containsExactly(new Bucket(Category.OT_RD, 240, false));
            Map<Category, Bucket> h = byCategory(OvertimeClassifier.classify(
                    List.of(iv("2027-09-02T05:00", "2027-09-02T07:00")), days, NIGHT));
            assertThat(h.get(Category.OT_PH_N).minutes()).isEqualTo(60);
            assertThat(h.get(Category.OT_PH).minutes()).isEqualTo(60);
        }

        @Test
        void overlappingIntervalsCountOnceAndSecondsAreTruncated() {
            List<Interval> in = List.of(iv("2027-09-01T17:00", "2027-09-01T19:00"),
                    iv("2027-09-01T18:00:40", "2027-09-01T19:30:59"));
            assertThat(OvertimeClassifier.minutes(in)).isEqualTo(150);
            assertThat(OvertimeClassifier.classify(in, days, NIGHT))
                    .containsExactly(new Bucket(Category.OT_WD, 150, false));
        }

        @Test
        void aNightWindowWithinOneDay() {
            NightWindow odd = new NightWindow(LocalTime.of(1, 0), LocalTime.of(5, 0));
            assertThat(odd.contains(LocalTime.of(0, 59))).isFalse();
            assertThat(odd.contains(LocalTime.of(1, 0))).isTrue();
            assertThat(odd.contains(LocalTime.of(5, 0))).isFalse();
            assertThat(NIGHT.contains(LocalTime.of(23, 0))).isTrue();
            assertThat(NIGHT.contains(LocalTime.of(6, 0))).isFalse();
        }
    }

    @Nested
    class Caps {

        @Test
        void monthlyCapWarnsFromTheThresholdAndBlocksAbove() {
            assertThat(OvertimeRules.cap("MONTH", 2400, 1000, 200, 80).level()).isEqualTo(Level.OK);
            assertThat(OvertimeRules.cap("MONTH", 2400, 1800, 120, 80).level()).isEqualTo(Level.WARN);
            assertThat(OvertimeRules.cap("MONTH", 2400, 2300, 100, 80).level()).isEqualTo(Level.WARN);
            var over = OvertimeRules.cap("MONTH", 2400, 2300, 200, 80);
            assertThat(over.level()).isEqualTo(Level.EXCEEDED);
            assertThat(over.excessMin()).isEqualTo(100);
            assertThat(OvertimeRules.cap("YEAR", 12000, 12500, 60, 80).excessMin()).isEqualTo(60);
        }

        @Test
        void dailyLimitCountsNormalHoursOnWorkingDaysOnly() {
            assertThat(OvertimeRules.daily(true, 480, 0, 240, 720, 720).level()).isEqualTo(Level.OK);
            assertThat(OvertimeRules.daily(true, 480, 120, 180, 720, 720).level()).isEqualTo(Level.EXCEEDED);
            assertThat(OvertimeRules.daily(false, 0, 0, 720, 720, 720).level()).isEqualTo(Level.OK);
            assertThat(OvertimeRules.daily(false, 0, 0, 721, 720, 720).level()).isEqualTo(Level.EXCEEDED);
        }
    }

    @Nested
    class Protected {

        final Map<String, ProtectedRule> rules = Map.of("PREGNANCY", ProtectedRule.BLOCK,
                "CHILD_UNDER_12M", ProtectedRule.CONSENT, "ELDERLY_WORKER", ProtectedRule.ALLOW);

        @Test
        void theStrictestRuleWinsAndUnknownTypesBlock() {
            assertThat(OvertimeRules.protectedOutcome(Set.of(), rules, false)).isEqualTo(ProtectedOutcome.NOT_PROTECTED);
            assertThat(OvertimeRules.protectedOutcome(Set.of("ELDERLY_WORKER"), rules, false))
                    .isEqualTo(ProtectedOutcome.ALLOWED);
            assertThat(OvertimeRules.protectedOutcome(Set.of("CHILD_UNDER_12M"), rules, false))
                    .isEqualTo(ProtectedOutcome.NEEDS_OWN_CONSENT);
            assertThat(OvertimeRules.protectedOutcome(Set.of("CHILD_UNDER_12M"), rules, true))
                    .isEqualTo(ProtectedOutcome.ALLOWED);
            assertThat(OvertimeRules.protectedOutcome(Set.of("CHILD_UNDER_12M", "PREGNANCY"), rules, true))
                    .isEqualTo(ProtectedOutcome.BLOCKED);
            assertThat(OvertimeRules.protectedOutcome(Set.of("MINOR"), rules, true)).isEqualTo(ProtectedOutcome.BLOCKED);
        }
    }

    @Nested
    class Paid {

        @Test
        void paidIsTheMinimumOfApprovedAndActualPerCategory() {
            List<ResultRow> rows = OvertimeRules.paid(
                    List.of(new Bucket(Category.OT_WD, 180, false), new Bucket(Category.OT_WD_N, 60, true)),
                    List.of(new Bucket(Category.OT_WD, 120, false), new Bucket(Category.OT_WD_N, 90, true),
                            new Bucket(Category.OT_RD, 30, false)),
                    false);
            assertThat(rows).containsExactly(new ResultRow(Category.OT_WD, 180, 120, 120, false),
                    new ResultRow(Category.OT_RD, 0, 30, 0, false),
                    new ResultRow(Category.OT_WD_N, 60, 90, 60, true));
        }

        @Test
        void approvedIsPaidForExemptEmployeesAndTheFlagSurvivesUnpaidMinutes() {
            assertThat(OvertimeRules.paid(List.of(new Bucket(Category.OT_WD, 180, false)), List.of(), true))
                    .containsExactly(new ResultRow(Category.OT_WD, 180, 0, 180, false));
            assertThat(OvertimeRules.paid(List.of(), List.of(new Bucket(Category.OT_PH_N, 45, true)), false))
                    .containsExactly(new ResultRow(Category.OT_PH_N, 0, 45, 0, true));
        }
    }
}
