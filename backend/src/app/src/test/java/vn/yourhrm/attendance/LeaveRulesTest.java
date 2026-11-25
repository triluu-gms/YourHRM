package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules.MonthFacts;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules.PlanDay;

/** The pure leave arithmetic (BR-ATT-057..061): leave years, duration, entitlement, ND145 proration, year end. */
class LeaveRulesTest {

    static final LocalDate MON = LocalDate.of(2027, 3, 1); // a Monday

    static List<PlanDay> week() {
        // Mon-Fri working, Sat half day, Sun rest
        return List.of(new PlanDay(MON, true, BigDecimal.ONE), new PlanDay(MON.plusDays(1), true, BigDecimal.ONE),
                new PlanDay(MON.plusDays(2), true, BigDecimal.ONE), new PlanDay(MON.plusDays(3), true, BigDecimal.ONE),
                new PlanDay(MON.plusDays(4), true, BigDecimal.ONE),
                new PlanDay(MON.plusDays(5), true, new BigDecimal("0.5")), new PlanDay(MON.plusDays(6), false, null));
    }

    @Test
    void leaveYearsFollowTheStartMonth() {
        assertThat(LeaveRules.leaveYearOf(LocalDate.of(2027, 3, 31), 1)).isEqualTo(2027);
        assertThat(LeaveRules.leaveYearOf(LocalDate.of(2027, 3, 31), 4)).isEqualTo(2026);
        assertThat(LeaveRules.leaveYearOf(LocalDate.of(2027, 4, 1), 4)).isEqualTo(2027);
        assertThat(LeaveRules.yearEnd(2026, 4)).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void workingDayLeaveSkipsRestDaysAndCountsHalfDays() {
        assertThat(LeaveRules.total(LeaveRules.countedDays(week(), "WORKING_DAY", "FULL", "FULL")))
                .isEqualByComparingTo("5.5");
        // starts Monday afternoon, ends Wednesday at noon
        assertThat(LeaveRules.total(LeaveRules.countedDays(week().subList(0, 3), "WORKING_DAY", "PM", "AM")))
                .isEqualByComparingTo("2");
        // one morning
        assertThat(LeaveRules.total(LeaveRules.countedDays(week().subList(0, 1), "WORKING_DAY", "AM", "AM")))
                .isEqualByComparingTo("0.5");
        // calendar-day leave (maternity) counts the rest day too
        assertThat(LeaveRules.total(LeaveRules.countedDays(week(), "CALENDAR_DAY", "FULL", "FULL")))
                .isEqualByComparingTo("7");
    }

    @Test
    void entitlementIsTheHigherOfFloorAndPolicyPlusSeniority() {
        BigDecimal one = BigDecimal.ONE;
        assertThat(LeaveRules.entitlement(new BigDecimal("12"), null, 4, 5, one)).isEqualByComparingTo("12");
        assertThat(LeaveRules.entitlement(new BigDecimal("12"), null, 5, 5, one)).isEqualByComparingTo("13");
        assertThat(LeaveRules.entitlement(new BigDecimal("12"), null, 11, 5, one)).isEqualByComparingTo("14");
        assertThat(LeaveRules.entitlement(new BigDecimal("14"), new BigDecimal("12"), 0, 5, one))
                .isEqualByComparingTo("14");
        assertThat(LeaveRules.entitlement(new BigDecimal("12"), new BigDecimal("15"), 0, 5, one))
                .isEqualByComparingTo("15");
        assertThat(LeaveRules.serviceYears(LocalDate.of(2020, 3, 2), LocalDate.of(2025, 3, 1))).isEqualTo(4);
        assertThat(LeaveRules.serviceYears(LocalDate.of(2020, 3, 2), LocalDate.of(2025, 3, 2))).isEqualTo(5);
    }

    @Test
    void nd145HalfMonthRule() {
        YearMonth m = YearMonth.of(2027, 3);
        assertThat(LeaveRules.counts(new MonthFacts(m, 22, 11, 31, 16), "ND145_HALF_MONTH")).isTrue();
        assertThat(LeaveRules.counts(new MonthFacts(m, 22, 10, 31, 16), "ND145_HALF_MONTH")).isFalse();
        // no schedule: calendar days decide
        assertThat(LeaveRules.counts(new MonthFacts(m, 0, 0, 31, 16), "ND145_HALF_MONTH")).isTrue();
        assertThat(LeaveRules.counts(new MonthFacts(m, 22, 0, 31, 15), "CALENDAR_DAYS")).isFalse();
        assertThat(LeaveRules.counts(new MonthFacts(m, 22, 0, 31, 1), "NONE")).isTrue();
    }

    @Test
    void monthlyStepsAddUpToTheEntitlement() {
        BigDecimal e = new BigDecimal("14");
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = 1; k <= 12; k++) {
            sum = sum.add(LeaveRules.monthlyStep(e, k));
        }
        assertThat(sum).isEqualByComparingTo("14");
        assertThat(LeaveRules.monthlyStep(e, 1)).isEqualByComparingTo("1.17");
        assertThat(LeaveRules.share(new BigDecimal("12"), 7)).isEqualByComparingTo("7");
    }

    @Test
    void yearEndCarriesUpToTheMaximumAndCarriedDaysExpireAfterUse() {
        var s = LeaveRules.yearEnd(new BigDecimal("8"), new BigDecimal("5"));
        assertThat(s.carried()).isEqualByComparingTo("5");
        assertThat(s.remainder()).isEqualByComparingTo("3");
        var debt = LeaveRules.yearEnd(new BigDecimal("-2"), new BigDecimal("5"));
        assertThat(debt.carried()).isEqualByComparingTo("-2");
        assertThat(debt.remainder()).isEqualByComparingTo("0");
        assertThat(LeaveRules.yearEnd(new BigDecimal("3"), null).remainder()).isEqualByComparingTo("3");
        assertThat(LeaveRules.carriedToExpire(new BigDecimal("5"), new BigDecimal("2"))).isEqualByComparingTo("3");
        assertThat(LeaveRules.carriedToExpire(new BigDecimal("5"), new BigDecimal("7"))).isEqualByComparingTo("0");
    }
}
