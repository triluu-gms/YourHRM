package vn.yourhrm.people.internal.infra.assignment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Idempotency markers of the assignment / salary streams in People's consumer-dedupe table {@code hr.inbox_message}
 * (ADR-0028 consumer template: advisory lock, check, insert in the transaction of the effect). Used for workflow
 * outcomes of proposals (consumer {@code hr.assignment.proposal} / {@code hr.salary.proposal}, event = the proposal id)
 * and for events the daily job published (consumer {@code hr.assignment.due} ..., event = the version id).
 * The inbox keeps 90 days (monthly partitions), longer than any lookback of the daily job.
 */
@Component
public class ProcessedMarkers {

    private final HrDb db;

    public ProcessedMarkers(HrDb db) {
        this.db = db;
    }

    /** Serialises concurrent handling of the same (consumer, id) until the transaction ends. */
    public void lock(String consumer, UUID id) {
        db.jdbc().sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", consumer + id)
                .query((rs, n) -> 1).single();
    }

    /** The status of the marker (PROCESSED / SKIPPED / FAILED), empty when none. */
    public Optional<String> status(String consumer, UUID id) {
        return db.jdbc().sql("""
                        select status from hr.inbox_message
                         where consumer = :c and event_id = :id and tenant_id = :t
                         order by received_at desc limit 1""")
                .param("c", consumer).param("id", id).param("t", TenantContext.current().tenantId())
                .query(String.class).optional();
    }

    public boolean exists(String consumer, UUID id) {
        return status(consumer, id).isPresent();
    }

    /** Records that {@code id} was handled with {@code status} (PROCESSED or SKIPPED). */
    public void mark(String consumer, UUID id, String eventType, String status) {
        db.jdbc().sql("""
                        insert into hr.inbox_message (consumer, event_id, tenant_id, event_type, processed_at, status,
                               attempts)
                        values (:c, :id, :t, :type, now(), :s, 1)""")
                .param("c", consumer).param("id", id).param("t", TenantContext.current().tenantId())
                .param("type", eventType).param("s", status).update();
    }
}
