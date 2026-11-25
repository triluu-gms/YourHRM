package vn.yourhrm.attendance.internal.application.schedule;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttInbox;
import vn.yourhrm.attendance.internal.infra.AttInbox.Outcome;
import vn.yourhrm.attendance.internal.infra.AttTransactional;

/**
 * Entry of a People event into T&amp;A: one T&amp;A transaction around the inbox dedupe and the handler (which runs in
 * a savepoint, so a failing handler leaves a FAILED inbox row and nothing else).
 */
@Component
class PeopleInbound {

    static final String ASSIGNMENT_CONSUMER = "att.AssignmentProjection";
    static final String EMPLOYMENT_CONSUMER = "att.EmploymentProjection";

    private final AttInbox inbox;

    PeopleInbound(AttInbox inbox) {
        this.inbox = inbox;
    }

    UUID contentId(String eventType, Object event) {
        return inbox.contentId(eventType, event);
    }

    @AttTransactional
    public Outcome receive(String consumer, UUID eventId, String eventType, Supplier<Boolean> handler) {
        return inbox.handle(consumer, eventId, eventType, handler);
    }
}
