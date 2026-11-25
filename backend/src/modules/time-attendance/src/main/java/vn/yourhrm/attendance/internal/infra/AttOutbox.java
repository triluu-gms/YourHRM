package vn.yourhrm.attendance.internal.infra;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import vn.yourhrm.attendance.api.AttendanceEvent;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.RequestOrigin;

/**
 * T&amp;A's transactional outbox (ADR-0028): the event row is inserted into {@code att.outbox_event} in the T&amp;A
 * transaction of the change, so the change and its event commit or roll back together; the relay delivers it at least
 * once and consumers dedupe on {@code event_id}. The same event is also published in-process.
 */
@Component
public class AttOutbox {

    private final AttDb db;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    public AttOutbox(AttDb db, JsonMapper json, ApplicationEventPublisher events) {
        this.db = db;
        this.json = json;
        this.events = events;
    }

    /** @return the event id ({@code event_id} of the envelope) */
    public UUID publish(AttendanceEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("T&A events are written inside the T&A transaction of the change");
        }
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into att.outbox_event (tenant_id, id, legal_entity_id, aggregate_type, aggregate_id,
                               event_type, schema_version, effective_date, correlation_id, is_retro, payload)
                        values (:t, :id, :le, :at, :aid, :type, :sv, :eff, :corr, :retro, cast(:payload as jsonb))""")
                .param("t", event.tenantId()).param("id", id).param("le", event.legalEntityId())
                .param("at", event.aggregateType()).param("aid", event.aggregateId())
                .param("type", event.eventType()).param("sv", event.schemaVersion())
                .param("eff", event.effectiveDate())
                .param("corr", RequestOrigin.optional().map(RequestOrigin::correlationId).orElse(null))
                .param("retro", event.retro()).param("payload", json.writeValueAsString(event))
                .update();
        events.publishEvent(event);
        return id;
    }
}
