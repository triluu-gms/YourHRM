package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.attendance.internal.domain.report.ReportFigures;
import vn.yourhrm.attendance.internal.domain.report.ReportFigures.CapUsage;
import vn.yourhrm.attendance.internal.domain.report.ReportFigures.LeaveMovements;
import vn.yourhrm.attendance.internal.domain.report.TimesheetGrid;
import vn.yourhrm.attendance.internal.domain.report.TimesheetGrid.CodeQty;
import vn.yourhrm.attendance.internal.domain.report.TimesheetGrid.Day;
import vn.yourhrm.attendance.internal.domain.report.TimesheetGrid.Row;

/** Pure logic of the T&amp;A reports (BRD 03 §8): the timesheet grid R-01, cap usage R-04, leave movements R-06. */
class ReportLogicTest {

    static final UUID A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    static final UUID UNIT = UUID.randomUUID();
    static final LocalDate D1 = LocalDate.of(2027, 3, 1);

    static final Map<String, String> CATEGORY = Map.of("X", "WORKED_DAYS", "P", "PAID_LEAVE_DAYS", "Ô",
            "SI_LEAVE_DAYS", "L", "HOLIDAY_DAYS", "NE", "NOT_EMPLOYED_DAYS", "KL", "UNPAID_DAYS");

    static Day day(UUID e, int offset, String... codeQty) {
        List<CodeQty> codes = new java.util.ArrayList<>();
        for (int i = 0; i < codeQty.length; i += 2) {
            codes.add(new CodeQty(codeQty[i], new BigDecimal(codeQty[i + 1])));
        }
        return new Day(e, D1.plusDays(offset), UNIT, "PRESENT", codes.isEmpty() ? null : codes.getFirst().code(),
                codes, new BigDecimal("8.00"), 5, 0, new BigDecimal("1.50"), List.of());
    }

    // ---------------------------------------------------------------------------------------------- cells

    @Test
    void cellShowsFullHalfFractionalAndSplitDays() {
        assertThat(TimesheetGrid.cell(day(A, 0, "X", "1"))).isEqualTo("X");
        assertThat(TimesheetGrid.cell(day(A, 0, "P", "0.5"))).isEqualTo("P/2");
        assertThat(TimesheetGrid.cell(day(A, 0, "X", "0.25"))).isEqualTo("X(0.25)");
        assertThat(TimesheetGrid.cell(day(A, 0, "X", "0.5", "P", "0.5"))).isEqualTo("X/P");
        // zero quantities are ignored; no code at all falls back to the primary code, else empty
        assertThat(TimesheetGrid.cell(day(A, 0, "X", "0", "P", "1"))).isEqualTo("P");
        Day rest = new Day(A, D1, UNIT, "REST", null, List.of(), null, 0, 0, null, List.of());
        assertThat(TimesheetGrid.cell(rest)).isEmpty();
        Day primaryOnly = new Day(A, D1, UNIT, "REST", "NE", List.of(), null, 0, 0, null, List.of());
        assertThat(TimesheetGrid.cell(primaryOnly)).isEqualTo("NE");
    }

    // ------------------------------------------------------------------------------------------- assembly

    @Test
    void assemblesOneRowPerEmploymentInOrderWithACellPerDate() {
        List<Day> days = List.of(day(A, 0, "X", "1"), day(A, 1, "X", "0.5", "P", "0.5"), day(A, 3, "Ô", "1"),
                day(B, 0, "L", "1"), day(B, 2, "X", "1"),
                day(B, 9, "X", "1"));                       // outside the range: ignored
        List<Row> rows = TimesheetGrid.assemble(List.of(B, A), days, D1, D1.plusDays(3), CATEGORY);

        assertThat(rows).extracting(Row::employmentId).containsExactly(B, A);
        Row a = rows.get(1);
        assertThat(a.cells()).containsExactly("X", "X/P", "", "Ô");
        assertThat(a.missingDays()).isEqualTo(1);
        assertThat(a.codeTotals()).containsEntry("X", new BigDecimal("1.5")).containsEntry("P", new BigDecimal("0.5"))
                .containsEntry("Ô", new BigDecimal("1"));
        assertThat(a.categoryTotals()).containsEntry("WORKED_DAYS", new BigDecimal("1.5"))
                .containsEntry("PAID_LEAVE_DAYS", new BigDecimal("0.5"))
                .containsEntry("SI_LEAVE_DAYS", new BigDecimal("1"));
        assertThat(a.workedHours()).isEqualByComparingTo("24.00");
        assertThat(a.nightHours()).isEqualByComparingTo("4.50");
        assertThat(a.lateMin()).isEqualTo(15);
        assertThat(a.orgUnitId()).isEqualTo(UNIT);

        Row b = rows.getFirst();
        assertThat(b.cells()).containsExactly("L", "", "X", "");
        assertThat(b.missingDays()).isEqualTo(2);
    }

    @Test
    void anEmploymentWithoutDaysGetsAnEmptyRow() {
        List<Row> rows = TimesheetGrid.assemble(List.of(A), List.of(), D1, D1.plusDays(1), CATEGORY);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.cells()).containsExactly("", "");
            assertThat(r.categoryTotals()).isEmpty();
            assertThat(r.missingDays()).isEqualTo(2);
            assertThat(r.workedHours()).isEqualByComparingTo("0");
        });
    }

    @Test
    void unknownCodesAreTotalledAsUnmapped() {
        List<Row> rows = TimesheetGrid.assemble(List.of(A), List.of(day(A, 0, "ZZ", "1")), D1, D1, CATEGORY);
        assertThat(rows.getFirst().categoryTotals()).containsOnlyKeys(TimesheetGrid.UNMAPPED);
    }

    @Test
    void categoryColumnsFollowTheStatutoryOrderThenOthersAndTheGrandTotalsAddUp() {
        List<Day> days = List.of(day(A, 0, "KL", "1"), day(A, 1, "X", "1"), day(B, 0, "ZZ", "1"),
                day(B, 1, "P", "1"), day(B, 2, "X", "1"));
        List<Row> rows = TimesheetGrid.assemble(List.of(A, B), days, D1, D1.plusDays(2), CATEGORY);

        assertThat(TimesheetGrid.categories(rows)).containsExactly("WORKED_DAYS", "PAID_LEAVE_DAYS", "UNPAID_DAYS",
                TimesheetGrid.UNMAPPED);
        assertThat(TimesheetGrid.grandTotals(rows)).containsEntry("WORKED_DAYS", new BigDecimal("2"))
                .containsEntry("PAID_LEAVE_DAYS", new BigDecimal("1")).containsEntry("UNPAID_DAYS", new BigDecimal("1"))
                .containsEntry(TimesheetGrid.UNMAPPED, new BigDecimal("1"));
    }

    @Test
    void datesCoverTheRangeInclusive() {
        assertThat(TimesheetGrid.dates(LocalDate.of(2027, 2, 26), LocalDate.of(2027, 3, 1))).hasSize(4)
                .endsWith(LocalDate.of(2027, 3, 1));
    }

    // ---------------------------------------------------------------------------------------- overtime

    @Test
    void capUsageCountsOpeningApprovedAndPendingAgainstTheLedgerCap() {
        CapUsage m = ReportFigures.capUsage("STANDARD_200", 2400, 0, 1800, 300, 1500, 0);
        assertThat(m.usedHours()).isEqualByComparingTo("35.00");
        assertThat(m.capHours()).isEqualByComparingTo("40.00");
        assertThat(m.usedPercent()).isEqualByComparingTo("87.5");
        assertThat(m.paidHours()).isEqualByComparingTo("25.00");
        assertThat(m.breached()).isFalse();
        assertThat(m.overHours()).isEqualByComparingTo("0");

        CapUsage y = ReportFigures.capUsage("STANDARD_200", 12000, 600, 11700, 0, 0, 300);
        assertThat(y.usedHours()).isEqualByComparingTo("205.00");
        assertThat(y.usedPercent()).isEqualByComparingTo("102.5");
        assertThat(y.breached()).isTrue();
        assertThat(y.overHours()).isEqualByComparingTo("5.00");
        assertThat(y.overrideHours()).isEqualByComparingTo("5.00");
    }

    @Test
    void percentWithoutCapIsNullAndHoursRound() {
        assertThat(ReportFigures.percent(10, 0)).isNull();
        assertThat(ReportFigures.hours(100)).isEqualByComparingTo("1.67");
    }

    @Test
    void overtimeCategoriesAreAllPresentInLegalOrder() {
        Map<String, BigDecimal> m = ReportFigures.otByCategory(Map.of("OT-RD-N", 90L, "OT-WD", 120L));
        assertThat(m.keySet()).containsExactly("OT-WD", "OT-RD", "OT-PH", "OT-WD-N", "OT-RD-N", "OT-PH-N");
        assertThat(m.get("OT-WD")).isEqualByComparingTo("2.00");
        assertThat(m.get("OT-RD-N")).isEqualByComparingTo("1.50");
        assertThat(m.get("OT-PH")).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------------------------------- leave

    @Test
    void leaveMovementsFoldTheLedgerAndClosingIsTheSignedSum() {
        Map<String, BigDecimal> sums = Map.of("OPENING", new BigDecimal("3"), "ACCRUE", new BigDecimal("12"),
                "TAKE", new BigDecimal("-5.5"), "CANCEL", new BigDecimal("1"), "ADJUST", new BigDecimal("-0.5"),
                "EXPIRE", new BigDecimal("-1"), "ENCASH", new BigDecimal("-2"));
        LeaveMovements b = ReportFigures.leaveMovements(sums, new BigDecimal("2"), new BigDecimal("-4"));
        assertThat(b.opening()).isEqualByComparingTo("3");
        assertThat(b.carriedIn()).isEqualByComparingTo("2");
        assertThat(b.accrued()).isEqualByComparingTo("12");
        assertThat(b.taken()).isEqualByComparingTo("4.5");
        assertThat(b.adjusted()).isEqualByComparingTo("-0.5");
        assertThat(b.carriedOut()).isEqualByComparingTo("4");
        assertThat(b.expired()).isEqualByComparingTo("1");
        assertThat(b.encashed()).isEqualByComparingTo("2");
        // 3 + 2 + 12 - 4.5 - 0.5 - 4 - 1 - 2
        assertThat(b.closing()).isEqualByComparingTo("5");
        assertThat(b.opening().add(b.carriedIn()).add(b.accrued()).subtract(b.taken()).add(b.adjusted())
                .subtract(b.carriedOut()).subtract(b.expired()).subtract(b.encashed())).isEqualByComparingTo(
                        b.closing());
    }

    @Test
    void leaveMovementsOfAnEmptyLedgerAreZero() {
        LeaveMovements b = ReportFigures.leaveMovements(Map.of(), null, null);
        assertThat(b.closing()).isEqualByComparingTo("0");
        assertThat(b.taken()).isEqualByComparingTo("0");
    }
}
