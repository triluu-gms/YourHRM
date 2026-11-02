package vn.yourhrm.people.internal.infra;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.yourhrm.people.api.PeopleEvent;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.RequestOrigin;

/**
 * People's transactional outbox (NFR-PPL-002, ADR-0028): the event row is inserted into {@code hr.outbox_event} in the
 * People transaction of the change, so the change and its event commit or roll back together; the relay
 * (TECH:outbox) delivers it at least once and consumers dedupe on {@code event_id}. The same event is also published
 * in-process for listeners inside the monolith.
 */
@Component
public class HrOutbox {

    private final HrDb db;
    private final HrJson json;
    private final ApplicationEventPublisher events;

    public HrOutbox(HrDb db, HrJson json, ApplicationEventPublisher events) {
        this.db = db;
        this.json = json;
        this.events = events;
    }

    /**
     * Appends {@code event} to the outbox; the aggregate (bus partition key) is the employment, else the person.
     *
     * @return the event id ({@code event_id} of the envelope)
     */
    public UUID publish(PeopleEvent event) {
        String aggregateType = event.employmentId() != null ? "employment" : "person";
        UUID aggregateId = event.employmentId() != null ? event.employmentId() : event.personId();
        return publish(event, aggregateType, aggregateId);
    }

    /** Appends {@code event} with an explicit aggregate (e.g. a legal entity for headcount snapshots). */
    public UUID publish(PeopleEvent event, String aggregateType, UUID aggregateId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("People events are written inside the People transaction of the change");
        }
        UUID id = Ids.newId();
        String headers = event.initialLoad() ? "{\"initial_load\":true}" : "{}";
        db.jdbc().sql("""
                        insert into hr.outbox_event (tenant_id, id, legal_entity_id, aggregate_type, aggregate_id,
                               event_type, schema_version, effective_date, correlation_id, is_retro, person_id,
                               employment_id, payload, headers)
                        values (:t, :id, :le, :at, :aid, :type, :sv, :eff, :corr, :retro, :person, :emp,
                                cast(:payload as jsonb), cast(:headers as jsonb))""")
                .param("t", event.tenantId()).param("id", id).param("le", event.legalEntityId())
                .param("at", aggregateType).param("aid", aggregateId).param("type", event.eventType())
                .param("sv", event.schemaVersion()).param("eff", event.effectiveDate())
                .param("corr", RequestOrigin.optional().map(RequestOrigin::correlationId).orElse(null))
                .param("retro", event.retro()).param("person", event.personId()).param("emp", event.employmentId())
                .param("payload", json.write(event)).param("headers", headers)
                .update();
        events.publishEvent(event);
        return id;
    }
}
