package vn.yourhrm.attendance.internal.application.holiday;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.HolidayCalendarChanged;
import vn.yourhrm.attendance.api.HolidayCalendarChanged.Change;
import vn.yourhrm.attendance.internal.infra.AttOutbox;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.AssignmentRow;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.CalendarRow;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.PeriodRef;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Builds and writes {@code att.HolidayCalendarChanged} (BR-ATT-006) in the transaction of the change: which legal
 * entities use the calendar on the changed days, and which of their attendance periods are already CLOSED / LOCKED
 * (retro). Open days are recalculated by daily processing on this event; frozen days become retro deltas.
 */
@Component
class HolidayChangeEvents {

    private final HolidayCalendarStore store;
    private final AttOutbox outbox;

    HolidayChangeEvents(HolidayCalendarStore store, AttOutbox outbox) {
        this.store = store;
        this.outbox = outbox;
    }

    /** A change of the given dates of {@code calendar}. Nothing is written when {@code dates} is empty. */
    HolidayCalendarChanged datesChanged(CalendarRow calendar, Change change, Collection<LocalDate> dates) {
        if (dates.isEmpty()) {
            return null;
        }
        List<LocalDate> sorted = dates.stream().distinct().sorted().toList();
        Set<UUID> les = new LinkedHashSet<>();
        for (AssignmentRow a : store.assignments(calendar.id())) {
            if (sorted.stream().anyMatch(a::covers)) {
                les.add(a.legalEntityId());
            }
        }
        LocalDate from = sorted.getFirst();
        LocalDate to = sorted.getLast();
        List<UUID> frozen = store.frozenPeriods(les, from, to).stream()
                .filter(p -> sorted.stream().anyMatch(d -> !d.isBefore(p.start()) && !d.isAfter(p.end())))
                .map(PeriodRef::id).toList();
        return publish(new HolidayCalendarChanged(TenantContext.current().tenantId(), calendar.id(), calendar.code(),
                change, sorted, List.copyOf(les), from, to, frozen, !frozen.isEmpty()));
    }

    /**
     * An assignment of {@code calendar} to {@code legalEntityId} changed over {@code [from, to]} ({@code to}
     * inclusive, null = open-ended): the resolved calendar of that legal entity changed on those days.
     */
    HolidayCalendarChanged assignmentChanged(CalendarRow calendar, UUID legalEntityId, LocalDate from, LocalDate to) {
        List<UUID> frozen = store.frozenPeriods(List.of(legalEntityId), from, to).stream().map(PeriodRef::id).toList();
        return publish(new HolidayCalendarChanged(TenantContext.current().tenantId(), calendar.id(), calendar.code(),
                Change.ASSIGNMENT_CHANGED, List.of(), List.of(legalEntityId), from, to, frozen, !frozen.isEmpty()));
    }

    private HolidayCalendarChanged publish(HolidayCalendarChanged event) {
        outbox.publish(event);
        return event;
    }
}
