package vn.yourhrm.people.internal.infra.integration;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.api.IntegrationInbox.Outcome;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * People's consumer dedupe and dead-letter store on {@code hr.inbox_message} (ADR-0028 consumer template, US-PPL-082):
 * advisory lock on (consumer, event id), skip when the event was PROCESSED / SKIPPED, otherwise run the handler in a
 * savepoint of the caller's People transaction. A failing handler is rolled back to the savepoint and the event is
 * kept as FAILED with {@code attempts} and a short error (exception type and message, truncated; handlers never put P3
 * values in exception messages); a redelivery retries it. Must be called inside a People transaction.
 */
@Component
public class HrInbox {

    private static final Logger log = LoggerFactory.getLogger(HrInbox.class);
    private static final int MAX_ERROR = 500;

    /** One inbox row as listed for operations. */
    public record Message(String consumer, UUID eventId, String eventType, String status, int attempts,
            String lastError, Instant receivedAt, Instant processedAt) {
    }

    private final HrDb db;
    private final TransactionTemplate nested;

    public HrInbox(HrDb db, @Qualifier(HrDb.TX) PlatformTransactionManager hrTm) {
        this.db = db;
        this.nested = new TransactionTemplate(hrTm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * Handles {@code eventId} once for {@code consumer}. {@code handler} returns true when it applied something
     * (PROCESSED) and false when there was nothing to do (SKIPPED).
     */
    public Outcome handle(String consumer, UUID eventId, String eventType, Supplier<Boolean> handler) {
        UUID tenant = TenantContext.current().tenantId();
        db.jdbc().sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", consumer + eventId)
                .query((rs, n) -> 1).single();
        List<String> previous = db.jdbc().sql("""
                        select status from hr.inbox_message where consumer = :c and event_id = :e and tenant_id = :t
                         order by received_at desc limit 1""")
                .param("c", consumer).param("e", eventId).param("t", tenant).query(String.class).list();
        if (!previous.isEmpty() && !"FAILED".equals(previous.getFirst()) && !"RECEIVED".equals(previous.getFirst())) {
            return Outcome.DUPLICATE;
        }
        if (previous.isEmpty()) {
            db.jdbc().sql("""
                            insert into hr.inbox_message (consumer, event_id, tenant_id, event_type, status, attempts)
                            values (:c, :e, :t, :type, 'RECEIVED', 0)""")
                    .param("c", consumer).param("e", eventId).param("t", tenant).param("type", eventType).update();
        }
        Outcome outcome;
        String error = null;
        try {
            Boolean applied = nested.execute(s -> handler.get());
            outcome = Boolean.TRUE.equals(applied) ? Outcome.PROCESSED : Outcome.SKIPPED;
        } catch (RuntimeException ex) {
            outcome = Outcome.FAILED;
            String m = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            error = m.length() > MAX_ERROR ? m.substring(0, MAX_ERROR) : m;
            log.warn("Inbox {} event {} ({}) failed: {}", consumer, eventId, eventType, ex.getClass().getSimpleName());
        }
        db.jdbc().sql("""
                        update hr.inbox_message set status = :s, attempts = attempts + 1, last_error = :err,
                               processed_at = case when :s = 'FAILED' then null else now() end
                         where consumer = :c and event_id = :e and tenant_id = :t""")
                .param("s", outcome.name()).param("err", error).param("c", consumer).param("e", eventId)
                .param("t", tenant).update();
        return outcome;
    }

    /** Inbox rows of the current tenant with {@code status} (all statuses when null), newest first. */
    public List<Message> list(String status, String consumer, int limit) {
        return db.jdbc().sql("""
                        select consumer, event_id, event_type, status, attempts, last_error, received_at, processed_at
                          from hr.inbox_message
                         where tenant_id = :t and (cast(:s as text) is null or status = :s)
                           and (cast(:c as text) is null or consumer = :c)
                         order by received_at desc limit :n""")
                .param("t", TenantContext.current().tenantId()).param("s", status).param("c", consumer)
                .param("n", Math.min(Math.max(limit, 1), 500))
                .query((rs, n) -> new Message(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), instant(rs.getObject(7, OffsetDateTime.class)),
                        instant(rs.getObject(8, OffsetDateTime.class))))
                .list();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
