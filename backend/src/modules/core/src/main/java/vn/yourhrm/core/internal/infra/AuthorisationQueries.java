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
import vn.yourhrm.core.internal.domain.SystemRoleCodes;
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

    /**
     * Loads the snapshot: assignments not expired on {@code today} (future ones included), the grants derived from
     * organisation and employment data (BR-CORE-033), and their role matrices.
     */
    public EffectiveAuthorisation loadSnapshot(UUID tenantId, UUID userId, LocalDate today) {
        record UserRow(UUID employmentId, String status) {
        }
        UserRow user = jdbc.sql("""
                        select employment_id, status from core.user_account where tenant_id = :t and id = :u""")
                .param("t", tenantId).param("u", userId)
                .query((rs, n) -> new UserRow(rs.getObject(1, UUID.class), rs.getString(2))).optional()
                .orElse(new UserRow(null, null));

        List<Row> rows = new ArrayList<>(jdbc.sql("""
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
                .list());
        rows.addAll(derivedRows(tenantId, userId, user.employmentId(), user.status(), today));

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
        return new EffectiveAuthorisation(tenantId, userId, user.employmentId(), grants);
    }

    private record Row(UUID assignmentId, UUID roleId, String code, String nameVi, String nameEn, ScopeType type,
            Set<UUID> ids, boolean subtree, LocalDate from, LocalDate to) {
    }

    /**
     * Grants nobody assigns by hand (BR-CORE-033), valid for today only (the snapshot is re-derived after any
     * org-unit or user change, and at the latest when its cache entry expires):
     * <ul>
     *   <li>LINE_MANAGER on the subtrees of the org units the user manages today ({@code manager_user_id});</li>
     *   <li>ESS (status ACTIVE) or FORMER_EMPLOYEE (status FORMER_EMPLOYEE) with scope SELF when the user is
     *       linked to an employment.</li>
     * </ul>
     * A derived grant has no assignment id; it exists only while its tenant has the system role.
     */
    private List<Row> derivedRows(UUID tenantId, UUID userId, UUID employmentId, String status, LocalDate today) {
        Map<String, Row> roles = new HashMap<>();
        jdbc.sql("""
                        select id, code, name_vi, name_en from core.role
                         where tenant_id = :t and is_system and code in (:codes)""")
                .param("t", tenantId).param("codes", List.copyOf(SystemRoleCodes.DERIVED))
                .query(rs -> {
                    roles.put(rs.getString(2), new Row(null, rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getString(3), rs.getString(4), null, Set.of(), true, today, today.plusDays(1)));
                });
        List<Row> out = new ArrayList<>();
        Row lineManager = roles.get(SystemRoleCodes.LINE_MANAGER);
        if (lineManager != null) {
            Set<UUID> managed = new LinkedHashSet<>(jdbc.sql("""
                            select distinct org_unit_id from core.org_unit_version
                             where tenant_id = :t and manager_user_id = :u and status = 'ACTIVE'
                               and valid_during @> cast(:d as date)""")
                    .param("t", tenantId).param("u", userId).param("d", today)
                    .query((rs, n) -> rs.getObject(1, UUID.class)).list());
            if (!managed.isEmpty()) {
                out.add(derived(lineManager, ScopeType.ORG_UNIT, managed));
            }
        }
        if (employmentId != null) {
            String selfRole = "ACTIVE".equals(status) ? SystemRoleCodes.ESS
                    : "FORMER_EMPLOYEE".equals(status) ? SystemRoleCodes.FORMER_EMPLOYEE : null;
            if (selfRole != null && roles.containsKey(selfRole)) {
                out.add(derived(roles.get(selfRole), ScopeType.SELF, Set.of()));
            }
        }
        return out;
    }

    private static Row derived(Row role, ScopeType type, Set<UUID> ids) {
        return new Row(null, role.roleId(), role.code(), role.nameVi(), role.nameEn(), type, ids, true, role.from(),
                role.to());
    }

    /**
     * Candidate users for a recipient filter (BR-CORE-035): users who could hold {@code permission} today, through
     * an assignment or a derived grant. The caller still checks sign-in and evaluates each one's scope.
     */
    public List<UUID> candidatesFor(UUID tenantId, String permission, LocalDate today) {
        return jdbc.sql("""
                        with holders as (
                          select r.id, r.code, r.is_system from core.role r
                            join core.role_permission rp on rp.tenant_id = r.tenant_id and rp.role_id = r.id
                           where r.tenant_id = :t and rp.permission_code = :p)
                        select ra.user_id from core.role_assignment ra join holders h on h.id = ra.role_id
                         where ra.tenant_id = :t and ra.valid_from <= :d and (ra.valid_to is null or ra.valid_to > :d)
                        union
                        select v.manager_user_id from core.org_unit_version v
                         where v.tenant_id = :t and v.manager_user_id is not null and v.status = 'ACTIVE'
                           and v.valid_during @> cast(:d as date)
                           and exists (select 1 from holders h where h.is_system and h.code = 'LINE_MANAGER')
                        union
                        select u.id from core.user_account u
                         where u.tenant_id = :t and u.employment_id is not null
                           and exists (select 1 from holders h where h.is_system and h.code in ('ESS', 'FORMER_EMPLOYEE'))""")
                .param("t", tenantId).param("p", permission).param("d", today)
                .query(UUID.class).list();
    }

    /** Users with an assignment of role {@code roleCode} valid on {@code today}. */
    public List<UUID> holdersOfRole(UUID tenantId, String roleCode, LocalDate today) {
        return jdbc.sql("""
                        select distinct ra.user_id from core.role_assignment ra
                          join core.role r on r.tenant_id = ra.tenant_id and r.id = ra.role_id
                         where ra.tenant_id = :t and r.code = :c
                           and ra.valid_from <= :d and (ra.valid_to is null or ra.valid_to > :d)""")
                .param("t", tenantId).param("c", roleCode).param("d", today)
                .query(UUID.class).list();
    }

    /** May the user sign in now (status and expiry)? */
    public boolean mayAccess(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        select exists (select 1 from core.user_account where tenant_id = :t and id = :u
                                          and status in ('ACTIVE', 'FORMER_EMPLOYEE')
                                          and (expires_at is null or expires_at > now()))""")
                .param("t", tenantId).param("u", userId).query(Boolean.class).single();
    }

    /**
     * Active catalogue permissions of {@code modules} usable in a support session (BR-CORE-041): code -> action.
     * Privileged permissions (role design, role grants) and the tenant's own oversight of support sessions are
     * never available to an operator.
     */
    public Map<String, String> permissionsOfModules(Set<String> modules) {
        Map<String, String> out = new LinkedHashMap<>();
        if (modules.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select permission_code, action from core.permission
                         where is_active and not is_privileged and split_part(permission_code, '.', 1) in (:m)
                           and permission_code not like 'core.support_session.%'""")
                .param("m", List.copyOf(modules))
                .query(rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                });
        return out;
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
