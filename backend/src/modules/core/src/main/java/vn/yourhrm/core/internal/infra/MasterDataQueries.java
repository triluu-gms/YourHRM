package vn.yourhrm.core.internal.infra;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.yourhrm.shared.auth.api.ScopeType;

/** Existence checks against Core master data (scope ids of role assignments) and the tenant's own row. */
@Repository
public class MasterDataQueries {

    private final JdbcClient jdbc;

    public MasterDataQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the ids among {@code ids} that do NOT exist in the master table for the scope type. */
    public Set<UUID> missingScopeIds(UUID tenantId, ScopeType type, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        String table = switch (type) {
            case LEGAL_ENTITY -> "core.legal_entity";
            case LOCATION -> "core.location";
            case ORG_UNIT -> "core.org_unit";
            default -> throw new IllegalArgumentException("Scope " + type + " has no ids");
        };
        List<UUID> found = jdbc.sql("select id from " + table + " where tenant_id = :t and id in (:ids)")
                .param("t", tenantId).param("ids", List.copyOf(ids))
                .query((rs, n) -> rs.getObject(1, UUID.class)).list();
        Set<UUID> missing = new HashSet<>(ids);
        found.forEach(missing::remove);
        return missing;
    }

    /** The tenant's own row in saas.tenant (X-02 read-only exception; RLS returns only the own tenant). */
    public Optional<TenantInfo> tenant(UUID tenantId) {
        return jdbc.sql("""
                        select id, slug, name, state, default_locale, time_zone from saas.tenant where id = :t""")
                .param("t", tenantId)
                .query((rs, n) -> new TenantInfo(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional();
    }

    public record TenantInfo(UUID id, String slug, String name, String state, String defaultLocale, String timeZone) {
    }
}
