package vn.yourhrm.attendance.internal.application.overtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.yourhrm.attendance.internal.infra.AttInbox.Outcome;
import vn.yourhrm.people.api.PersonEvents.SpecialStatusChanged;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * T&amp;A's consumer of {@code people.SpecialStatusChanged} after People's commit. A failure never affects People's
 * change: the inbox keeps it as a dead letter ({@code GET /api/v1/attendance/integration/inbox}).
 */
@Component
class SpecialStatusListener {

    private static final Logger log = LoggerFactory.getLogger(SpecialStatusListener.class);

    private final SpecialStatusProjection projection;

    SpecialStatusListener(SpecialStatusProjection projection) {
        this.projection = projection;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(SpecialStatusChanged e) {
        if (!TenantContext.isBound() || !TenantContext.current().tenantId().equals(e.tenantId())
                || !SpecialStatusProjection.relevant(e)) {
            return;
        }
        try {
            if (projection.receive(e) == Outcome.FAILED) {
                log.warn("{} failed in T&A and is kept as a dead letter", e.eventType());
            }
        } catch (RuntimeException ex) {
            log.warn("{} not recorded in the T&A inbox: {}", e.eventType(), ex.getClass().getSimpleName());
        }
    }
}
