package vn.yourhrm.attendance.internal.application.overtime;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttInbox;
import vn.yourhrm.attendance.internal.infra.AttInbox.Outcome;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore;
import vn.yourhrm.people.api.PersonEvents.SpecialStatusChanged;

/**
 * Feeds {@code att.ext_hr_special_status} (D5) from {@code people.SpecialStatusChanged} through T&amp;A's inbox
 * (consumer {@code att.SpecialStatusProjection}). Only the protected-worker types T&amp;A rules use are kept (no union
 * / party membership), with type and validity only; a cancelled status is deleted.
 */
@Component
public class SpecialStatusProjection {

    static final String CONSUMER = "att.SpecialStatusProjection";

    static final Set<String> TYPES = Set.of("PREGNANCY", "CHILD_UNDER_12M", "DISABILITY", "MINOR",
            "ELDERLY_WORKER", "HAZARDOUS_JOB");

    private final AttInbox inbox;
    private final OvertimeStore store;

    SpecialStatusProjection(AttInbox inbox, OvertimeStore store) {
        this.inbox = inbox;
        this.store = store;
    }

    static boolean relevant(SpecialStatusChanged e) {
        return e.statusType() != null && TYPES.contains(e.statusType());
    }

    @AttTransactional
    public Outcome receive(SpecialStatusChanged e) {
        UUID eventId = inbox.contentId(e.eventType(), e);
        return inbox.handle(CONSUMER, eventId, e.eventType(), () -> {
            apply(e, eventId);
            return true;
        });
    }

    private void apply(SpecialStatusChanged e, UUID eventId) {
        if ("CANCELLED".equals(e.change())) {
            store.deleteSpecialStatus(e.specialStatusId());
        } else {
            store.upsertSpecialStatus(e.specialStatusId(), e.personId(),
                    "HAZARDOUS_JOB".equals(e.statusType()) ? e.employmentId() : null, e.statusType(), e.validFrom(),
                    e.validTo(), eventId);
        }
    }
}
