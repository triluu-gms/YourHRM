package vn.yourhrm.core.internal.application;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * The tenant's view of platform support access (BR-CORE-041, US-CORE-042): the support grants that were used or
 * tried, and everything done under each, read from the tenant audit (request log + row audit attributed to the
 * operator through {@code app.operator_id} / {@code app.support_grant_id}).
 */
@Service
public class SupportSessionService {

    private final JdbcClient jdbc;

    public SupportSessionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SupportSessionView(UUID supportGrantId, UUID operatorId, Instant firstSeen, Instant lastSeen,
            long requests, long refusedRequests, long changes) {
    }

    public record SupportEventView(Instant occurredAt, String action, String objectType, UUID objectId,
            Map<String, String> details) {
    }

    @Transactional(readOnly = true)
    public List<SupportSessionView> sessions() {
        return jdbc.sql("""
                        select support_grant_id, operator_id, min(occurred_at), max(occurred_at),
                               count(*) filter (where action = 'SUPPORT_ACCESS'),
                               count(*) filter (where action = 'SUPPORT_ACCESS_DENIED'),
                               count(*) filter (where action in ('INSERT', 'UPDATE', 'DELETE'))
                          from audit.audit_event
                         where tenant_id = :t and operator_id is not null
                         group by support_grant_id, operator_id
                         order by max(occurred_at) desc""")
                .param("t", TenantContext.current().tenantId())
                .query((rs, n) -> new SupportSessionView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        instant(rs.getTimestamp(3)), instant(rs.getTimestamp(4)), rs.getLong(5), rs.getLong(6),
                        rs.getLong(7)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<SupportEventView> events(UUID supportGrantId) {
        return jdbc.sql("""
                        select occurred_at, action, object_type, object_id,
                               array(select key from jsonb_each_text(coalesce(change, '{}')) order by key) as k,
                               array(select value from jsonb_each_text(coalesce(change, '{}')) order by key) as v
                          from audit.audit_event
                         where tenant_id = :t and support_grant_id = :g
                         order by occurred_at, id""")
                .param("t", TenantContext.current().tenantId()).param("g", supportGrantId)
                .query((rs, n) -> new SupportEventView(instant(rs.getTimestamp(1)), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), details(rs)))
                .list();
    }

    private static Map<String, String> details(ResultSet rs) throws SQLException {
        Array keys = rs.getArray("k");
        Array values = rs.getArray("v");
        Map<String, String> out = new LinkedHashMap<>();
        if (keys != null && values != null) {
            Object[] k = (Object[]) keys.getArray();
            Object[] v = (Object[]) values.getArray();
            for (int i = 0; i < k.length; i++) {
                out.put(String.valueOf(k[i]), v[i] == null ? null : String.valueOf(v[i]));
            }
        }
        return out;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
