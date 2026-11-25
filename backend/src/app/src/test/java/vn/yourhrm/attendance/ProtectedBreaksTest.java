package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.domain.timesheet.DayCalculator;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.DayResult;
import vn.yourhrm.attendance.internal.domain.timesheet.ProtectedBreaks;

/** BR-ATT-035 / VN-16: statutory paid short breaks incl. the menstrual break (30 min, 3 days per month). */
class ProtectedBreaksTest {

    static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void theLargerEntitlementAppliesNeverTheSum() {
        assertThat(ProtectedBreaks.larger(60, 0, 30)).isEqualTo(60);
        assertThat(ProtectedBreaks.larger(0, 0, 30)).isEqualTo(30);
        assertThat(ProtectedBreaks.larger(0, 60, 30)).isEqualTo(60);
        assertThat(ProtectedBreaks.larger(null, -5, 0)).isZero();
        assertThat(ProtectedBreaks.larger()).isZero();
    }

    @Test
    void atMostTheMonthlyNumberOfDeclaredDaysIsCreditedInDateOrder() {
        var declared = List.of(d(11, 20), d(11, 3), d(11, 4), d(11, 5), d(12, 1));
        assertThat(ProtectedBreaks.credited(declared, 3)).containsExactly(d(11, 3), d(11, 4), d(11, 5), d(12, 1));
        assertThat(ProtectedBreaks.credited(declared, 5)).hasSize(5);
    }

    @Test
    void creditingIsDeterministicAndIgnoresDuplicates() {
        var a = ProtectedBreaks.credited(List.of(d(11, 5), d(11, 3), d(11, 3), d(11, 9), d(11, 1)), 3);
        var b = ProtectedBreaks.credited(List.of(d(11, 1), d(11, 9), d(11, 3), d(11, 5)), 3);
        assertThat(a).isEqualTo(b).containsExactly(d(11, 1), d(11, 3), d(11, 5));
    }

    @Test
    void noLimitOrNoDeclarationsCreditsNothing() {
        assertThat(ProtectedBreaks.credited(List.of(d(11, 3)), 0)).isEmpty();
        assertThat(ProtectedBreaks.credited(List.of(), 3)).isEmpty();
        assertThat(ProtectedBreaks.credited(null, 3)).isEmpty();
    }

    @Test
    void monthlyDeclarationLimit() {
        assertThat(ProtectedBreaks.canDeclare(0, 3)).isTrue();
        assertThat(ProtectedBreaks.canDeclare(2, 3)).isTrue();
        assertThat(ProtectedBreaks.canDeclare(3, 3)).isFalse();
        assertThat(ProtectedBreaks.canDeclare(0, 0)).isFalse();
    }

    @Test
    void thirtyMenstrualMinutesCountAsWorkingTimeOnAShortDay() {
        // 08:00-16:30 with lunch: 7.5 h worked; the 30 protected minutes fill the shortfall up to 8 h
        LocalDate day = DayCalculatorTest.D;
        DayResult r = DayCalculator.compute(DayCalculatorTest.input("WORKDAY", DayCalculatorTest.office(null),
                List.of(DayCalculatorTest.at(day, "08:00", "IN"), DayCalculatorTest.at(day, "16:30", "OUT")),
                List.of(), "FIRST_LAST", ProtectedBreaks.larger(0, 0, 30)));
        assertThat(r.protectedBreakMin()).isEqualTo(30);
        assertThat(r.earlyMin()).isZero();
        assertThat(r.workedHours()).isEqualByComparingTo("8.00");
        assertThat(r.exceptions()).isEmpty();
    }
}
