package vn.yourhrm.core.internal.infra;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.yourhrm.shared.common.tenancy.SupportSession;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Appends application-level events to the tenant audit ({@code audit.audit_event}, INSERT-only, ADR-0027) next to
 * the row audit the DDL triggers write: business actions of modules (BR-CORE-090), security events (BR-CORE-091),
 * sensitive reads (BR-CORE-034) and platform support access (BR-CORE-041). Runs in the caller's transaction, whose
 * tenant context satisfies RLS; request id, purpose, channel, client address, user agent and correlation id come
 * from it ({@code app.*}, set by the transaction manager from the bound request origin).
 */
@Repository
public class AuditWriter {

    /** One event. {@code details} become a string-valued jsonb object in {@code change}. */
    public record Event(UUID tenantId, String actorType, UUID actorId, UUID operatorId, UUID supportGrantId,
            String action, String objectSchema, String objectType, UUID objectId, UUID legalEntityId,
            Map<String, String> details, boolean sensitiveRead) {

        public Event(UUID tenantId, String actorType, UUID actorId, UUID operatorId, UUID supportGrantId,
                String action, String objectType, UUID objectId, UUID legalEntityId, Map<String, String> details,
                boolean sensitiveRead) {
            this(tenantId, actorType, actorId, operatorId, supportGrantId, action, null, objectType, objectId,
                    legalEntityId, details, sensitiveRead);
        }

        /** An event of the bound caller: the user, the support operator, or SYSTEM for a job without a user. */
        public static Event ofCurrentActor(String action, String objectSchema, String objectType, UUID objectId,
                UUID legalEntityId, Map<String, String> details, boolean sensitiveRead) {
            TenantContext ctx = TenantContext.current();
            var support = SupportSession.optional();
            String actorType = support.isPresent() ? "OPERATOR" : ctx.userAccountId() == null ? "SYSTEM" : "USER";
            return new Event(ctx.tenantId(), actorType, ctx.userAccountId(),
                    support.map(SupportSession::operatorId).orElse(null),
                    support.map(SupportSession::supportGrantId).orElse(null), action, objectSchema, objectType,
                    objectId, legalEntityId, details, sensitiveRead);
        }
    }

    private final JdbcClient jdbc;

    public AuditWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(Event e) {
        String[] keys = e.details().keySet().toArray(String[]::new);
        String[] values = e.details().values().stream().map(v -> v == null ? "" : v).toArray(String[]::new);
        jdbc.sql("""
                        insert into audit.audit_event (tenant_id, actor_type, actor_id, operator_id, support_grant_id,
                               action, object_schema, object_type, object_id, legal_entity_id, purpose, request_id,
                               change, is_sensitive_read, channel, client_ip, user_agent, correlation_id)
                        values (:t, :actorType, :actor, :operator, :grant, :action, :schema, :objectType, :objectId,
                                :le, sys.current_purpose(), sys.current_request_id(),
                                jsonb_object(cast(:keys as text[]), cast(:vals as text[])), :sensitive,
                                audit.current_channel(), audit.current_client_ip(), audit.current_user_agent(),
                                audit.current_correlation_id())""")
                .param("t", e.tenantId()).param("actorType", e.actorType()).param("actor", e.actorId())
                .param("operator", e.operatorId()).param("grant", e.supportGrantId()).param("action", e.action())
                .param("schema", e.objectSchema()).param("objectType", e.objectType()).param("objectId", e.objectId())
                .param("le", e.legalEntityId()).param("keys", keys).param("vals", values)
                .param("sensitive", e.sensitiveRead())
                .update();
    }
}
