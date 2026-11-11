package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.yourhrm.people.api.AssignmentEvents.CostShare;
import vn.yourhrm.people.internal.domain.assignment.CostSplit;
import vn.yourhrm.people.internal.domain.assignment.ManagerResolution;
import vn.yourhrm.people.internal.domain.assignment.WorkingDays;

/** Pure rules of the assignment stream (US-PPL-034, US-PPL-040). */
class AssignmentRulesTest {

    final UUID dev = UUID.randomUUID();
    final UUID lead = UUID.randomUUID();
    final UUID head = UUID.randomUUID();
    final UUID director = UUID.randomUUID();
    final Map<UUID, UUID> reportsTo = Map.of(dev, lead, lead, head, head, director);

    Optional<UUID> resolve(Map<UUID, UUID> incumbents, UUID self) {
        return ManagerResolution.fromPosition(dev, p -> Optional.ofNullable(reportsTo.get(p)),
                p -> Optional.ofNullable(incumbents.get(p)), self);
    }

    @Test
    void theManagerIsTheIncumbentOfTheManagerPositionOrTheNextLevelUpWhenVacant() {
        UUID leadPerson = UUID.randomUUID();
        UUID directorPerson = UUID.randomUUID();
        UUID self = UUID.randomUUID();
        assertThat(resolve(Map.of(lead, leadPerson, director, directorPerson), self)).contains(leadPerson);
        assertThat(resolve(Map.of(director, directorPerson), self)).contains(directorPerson);   // lead, head vacant
        assertThat(resolve(Map.of(), self)).isEmpty();
        assertThat(resolve(Map.of(lead, self, director, directorPerson), self)).contains(directorPerson);
    }

    @Test
    void aCyclicHierarchyStops() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, UUID> cycle = Map.of(a, b, b, a);
        assertThat(ManagerResolution.fromPosition(a, p -> Optional.ofNullable(cycle.get(p)), p -> Optional.empty(),
                UUID.randomUUID())).isEmpty();
    }

    @Test
    void costSplitTotalsExactly100() {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        assertThat(CostSplit.problem(List.of(new CostShare(x, new BigDecimal("60")), new CostShare(y, new BigDecimal("40")))))
                .isEmpty();
        assertThat(CostSplit.problem(List.of(new CostShare(x, new BigDecimal("60")), new CostShare(y, new BigDecimal("30")))))
                .hasValueSatisfying(p -> assertThat(p).contains("90"));
        assertThat(CostSplit.problem(List.of(new CostShare(x, new BigDecimal("50")), new CostShare(x, new BigDecimal("50")))))
                .isPresent();
        assertThat(CostSplit.primary(List.of(new CostShare(x, new BigDecimal("40")), new CostShare(y, new BigDecimal("60")))))
                .isEqualTo(y);
    }

    @Test
    void workingDaysAreCountedPerCalendarYearAndForNotice() {
        LocalDate monday = LocalDate.of(2026, 12, 28);
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        var monToSat = (java.util.function.Predicate<LocalDate>) d -> d.getDayOfWeek() != DayOfWeek.SUNDAY;
        // 28.12.2026 .. 03.01.2027 exclusive of 04.01: Mon-Thu 2026 (4) + Fri, Sat 2027 (2), Sunday off
        assertThat(WorkingDays.countInYear(monday, monday.plusDays(7), 2026, monToSat)).isEqualTo(4);
        assertThat(WorkingDays.countInYear(monday, monday.plusDays(7), 2027, monToSat)).isEqualTo(2);
        // told on Wednesday 23.12, starting Monday 28.12: Thu, Fri, Sat = 3 working days of notice
        assertThat(WorkingDays.noticeDays(LocalDate.of(2026, 12, 23), monday, monToSat)).isEqualTo(3);
    }
}
