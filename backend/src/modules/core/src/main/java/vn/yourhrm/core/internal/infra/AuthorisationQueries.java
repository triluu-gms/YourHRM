package vn.yourhrm.core.internal.infra;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.yourhrm.core.internal.domain.FieldMaskLevel;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation.AssignmentGrant;
import vn.yourhrm.core.internal.domain.authz.ScopeResolver;
import vn.yourhrm.shared.auth.api.ScopeType;

/**
 * Read-side SQL for the authorisation service (JdbcClient; jOOQ codegen is deferred). Must run inside a
 * transaction (tenant context set); every statement also filters {@code tenant_id} explicitly.
 */
@Repository
public class AuthorisationQueries implements ScopeResolver.OrgTree {

    private final JdbcClient jdbc;

    public AuthorisationQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Loads the snapshot: assignments not expired on {@code today} (future ones included) + their role matrices. */
    public EffectiveAuthorisation loadSnapshot(UUID tenantId, UUID userId, LocalDate today) {
        UUID employmentId = jdbc.sql("""
                        select employment_id from core.user_account where tenant_id = :t and id = :u""")
                .param("t", tenantId).param("u", userId)
                .query((rs, n) -> rs.getObject(1, UUID.class)).optional().orElse(null);

        record Row(UUID assignmentId, UUID roleId, String code, String nameVi, String nameEn, ScopeType type,
                Set<UUID> ids, boolean subtree, LocalDate from, LocalDate to) {
        }
        List<Row> rows = jdbc.sql("""
                        select ra.id, ra.role_id, r.code, r.name_vi, r.name_en, ra.scope_type, ra.scope_ids,
                               ra.include_subtree, ra.valid_from, ra.valid_to
                          from core.role_assignment ra
                          join core.role r on r.tenant_id = ra.tenant_id and r.id = ra.role_id
                         where ra.tenant_id = :t and ra.user_id = :u
                           and (ra.valid_to is null or ra.valid_to > :today)
                         order by ra.valid_from, ra.id""")
                .param("t", tenantId).param("u", userId).param("today", today)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), ScopeType.valueOf(rs.getString(6)),
                        uuidSet(rs, 7), rs.getBoolean(8), rs.getObject(9, LocalDate.class),
                        rs.getObject(10, LocalDate.class)))
                .list();

        Map<UUID, Map<String, FieldMaskLevel>> matrices = new HashMap<>();
        Set<UUID> roleIds = new LinkedHashSet<>();
        rows.forEach(r -> roleIds.add(r.roleId()));
        if (!roleIds.isEmpty()) {
            jdbc.sql("""
                            select rp.role_id, rp.permission_code, rp.field_mask_level
                              from core.role_permission rp
                              join core.permission p on p.permission_code = rp.permission_code and p.is_active
                             where rp.tenant_id = :t and rp.role_id in (:roles)""")
                    .param("t", tenantId).param("roles", List.copyOf(roleIds))
                    .query(rs -> {
                        matrices.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                                .put(rs.getString(2), FieldMaskLevel.valueOf(rs.getString(3)));
                    });
        }
        List<AssignmentGrant> grants = new ArrayList<>();
        for (Row r : rows) {
            grants.add(new AssignmentGrant(r.assignmentId(), r.roleId(), r.code(), r.nameVi(), r.nameEn(), r.type(),
                    r.ids(), r.subtree(), r.from(), r.to(), matrices.getOrDefault(r.roleId(), Map.of())));
        }
        return new EffectiveAuthorisation(tenantId, userId, employmentId, grants);
    }

    /** Org-unit subtrees as of a date via the materialised ltree path (DESIGN.md §5.3). */
    @Override
    public Set<UUID> subtreeOf(UUID tenantId, Set<UUID> roots, LocalDate asOf) {
        if (roots.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.sql("""
                        select distinct d.org_unit_id
                          from core.org_unit_version r
                          join core.org_unit_version d
                            on d.tenant_id = r.tenant_id and d.path <@ r.path and d.valid_during @> cast(:d as date)
                         where r.tenant_id = :t
                           and r.org_unit_id in (:roots)
                           and r.valid_during @> cast(:d as date)""")
                .param("t", tenantId).param("roots", List.copyOf(roots)).param("d", asOf)
                .query((rs, n) -> rs.getObject(1, UUID.class)).list());
    }

    /** Legal entity and location of an org unit as of a date. */
    public Optional<UnitPlacement> orgUnitPlacement(UUID tenantId, UUID orgUnitId, LocalDate asOf) {
        return jdbc.sql("""
                        select legal_entity_id, location_id from core.org_unit_version
                         where tenant_id = :t and org_unit_id = :id and valid_during @> cast(:d as date)""")
                .param("t", tenantId).param("id", orgUnitId).param("d", asOf)
                .query((rs, n) -> new UnitPlacement(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional();
    }

    /** Legal entity of a location. */
    public Optional<UUID> locationLegalEntity(UUID tenantId, UUID locationId) {
        return jdbc.sql("select legal_entity_id from core.location where tenant_id = :t and id = :id")
                .param("t", tenantId).param("id", locationId)
                .query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    public record UnitPlacement(UUID legalEntityId, UUID locationId) {
    }

    static Set<UUID> uuidSet(ResultSet rs, int column) throws SQLException {
        Array array = rs.getArray(column);
        Set<UUID> out = new LinkedHashSet<>();
        if (array != null) {
            for (Object o : (Object[]) array.getArray()) {
                out.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
            }
        }
        return out;
    }
}
