package vn.yourhrm.core.internal.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CoreEvents.RoleChanged;
import vn.yourhrm.core.internal.domain.FieldMaskLevel;
import vn.yourhrm.core.internal.domain.Role;
import vn.yourhrm.core.internal.domain.RolePermission;
import vn.yourhrm.core.internal.infra.RoleAssignmentRepository;
import vn.yourhrm.core.internal.infra.RolePermissionRepository;
import vn.yourhrm.core.internal.infra.RoleRepository;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/** Role administration (US-CORE-034): custom roles, copy, permission matrix; system roles are read-only. */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final RoleAssignmentRepository assignments;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;

    public RoleService(RoleRepository roles, RolePermissionRepository rolePermissions,
            RoleAssignmentRepository assignments, JdbcClient jdbc, ApplicationEventPublisher events,
            BusinessClock clock) {
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    public record RoleSummary(UUID id, String code, String nameVi, String nameEn, boolean system,
            int permissionCount, int version) {
    }

    public record RoleDetail(UUID id, String code, String nameVi, String nameEn, boolean system,
            Integer templateVersion, int version, List<Grant> permissions) {
    }

    public record Grant(String code, FieldMaskLevel fieldMaskLevel) {
    }

    @Transactional(readOnly = true)
    public List<RoleSummary> list(String query) {
        UUID tenant = TenantContext.current().tenantId();
        String q = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        return jdbc.sql("""
                        select r.id, r.code, r.name_vi, r.name_en, r.is_system, r.version,
                               (select count(*) from core.role_permission rp
                                 where rp.tenant_id = r.tenant_id and rp.role_id = r.id) as permission_count
                          from core.role r
                         where r.tenant_id = :t
                           and (cast(:q as text) is null or lower(r.code) like :q or lower(r.name_vi) like :q
                                or lower(r.name_en) like :q)
                         order by r.is_system desc, r.code""")
                .param("t", tenant).param("q", q)
                .query((rs, n) -> new RoleSummary(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name_vi"), rs.getString("name_en"), rs.getBoolean("is_system"),
                        rs.getInt("permission_count"), rs.getInt("version")))
                .list();
    }

    @Transactional(readOnly = true)
    public RoleDetail get(UUID roleId) {
        Role role = load(roleId);
        return detail(role, rolePermissions.findByTenantIdAndRoleId(role.getTenantId(), role.getId()));
    }

    @Transactional
    public RoleDetail create(String code, String nameVi, String nameEn, List<Grant> grants) {
        UUID tenant = TenantContext.current().tenantId();
        if (roles.existsByTenantIdAndCode(tenant, code)) {
            throw ApiException.conflict("role-code-taken", "A role with code " + code + " already exists");
        }
        Role role = roles.save(Role.createCustom(tenant, Ids.newId(), code, nameVi, nameEn, clock.now()));
        List<Grant> normalized = validateGrants(grants == null ? List.of() : grants);
        List<RolePermission> saved = rolePermissions.saveAll(normalized.stream()
                .map(g -> new RolePermission(tenant, role.getId(), g.code(), g.fieldMaskLevel())).toList());
        events.publishEvent(new RoleChanged(tenant, role.getId(), "CREATED"));
        return detail(role, saved);
    }

    @Transactional
    public RoleDetail update(UUID roleId, String nameVi, String nameEn, int expectedVersion) {
        Role role = load(roleId);
        role.assertMutable();
        checkVersion(role, expectedVersion);
        role.rename(nameVi, nameEn, clock.now());
        roles.saveAndFlush(role);
        events.publishEvent(new RoleChanged(role.getTenantId(), role.getId(), "UPDATED"));
        return get(roleId);
    }

    @Transactional
    public void delete(UUID roleId) {
        Role role = load(roleId);
        role.assertMutable();
        if (assignments.existsForRole(role.getTenantId(), role.getId())) {
            throw ApiException.conflict("role-in-use",
                    "Role " + role.getCode() + " has (or had) assignments; revoke them, or keep the role for history");
        }
        rolePermissions.deleteAll(rolePermissions.findByTenantIdAndRoleId(role.getTenantId(), role.getId()));
        roles.delete(role);
        events.publishEvent(new RoleChanged(role.getTenantId(), role.getId(), "DELETED"));
    }

    /** Copies any role (system roles included) into a new custom role with the same matrix. */
    @Transactional
    public RoleDetail copy(UUID sourceRoleId, String code, String nameVi, String nameEn) {
        Role source = load(sourceRoleId);
        List<Grant> grants = rolePermissions.findByTenantIdAndRoleId(source.getTenantId(), source.getId()).stream()
                .map(rp -> new Grant(rp.getPermissionCode(), rp.getFieldMaskLevel())).toList();
        return create(code, nameVi, nameEn, grants);
    }

    /** Replaces the permission matrix (PUT semantics); applied as a diff so the row audit shows real changes. */
    @Transactional
    public RoleDetail replacePermissions(UUID roleId, List<Grant> grants, Integer expectedVersion) {
        Role role = load(roleId);
        role.assertMutable();
        if (expectedVersion != null) {
            checkVersion(role, expectedVersion);
        }
        UUID tenant = role.getTenantId();
        Map<String, FieldMaskLevel> wanted = new LinkedHashMap<>();
        validateGrants(grants).forEach(g -> wanted.put(g.code(), g.fieldMaskLevel()));
        Map<String, RolePermission> current = new HashMap<>();
        rolePermissions.findByTenantIdAndRoleId(tenant, roleId).forEach(rp -> current.put(rp.getPermissionCode(), rp));

        List<RolePermission> toDelete = current.values().stream()
                .filter(rp -> !wanted.containsKey(rp.getPermissionCode())).toList();
        rolePermissions.deleteAll(toDelete);
        rolePermissions.flush();
        List<RolePermission> toSave = new ArrayList<>();
        wanted.forEach((code, mask) -> {
            RolePermission existing = current.get(code);
            if (existing == null) {
                toSave.add(new RolePermission(tenant, roleId, code, mask));
            } else if (existing.getFieldMaskLevel() != mask) {
                existing.changeMask(mask);
                toSave.add(existing);
            }
        });
        rolePermissions.saveAll(toSave);
        role.rename(role.getNameVi(), role.getNameEn(), clock.now()); // bumps version + updated_at
        roles.saveAndFlush(role);
        events.publishEvent(new RoleChanged(tenant, roleId, "PERMISSIONS_CHANGED"));
        return get(roleId);
    }

    private List<Grant> validateGrants(List<Grant> grants) {
        Map<String, FieldMaskLevel> unique = new LinkedHashMap<>();
        for (Grant g : grants) {
            if (unique.put(g.code(), g.fieldMaskLevel() == null ? FieldMaskLevel.NONE : g.fieldMaskLevel()) != null) {
                throw ApiException.unprocessable("duplicate-permission", "Permission " + g.code() + " listed twice");
            }
        }
        if (!unique.isEmpty()) {
            Set<String> active = Set.copyOf(jdbc.sql("""
                            select permission_code from core.permission
                             where is_active and permission_code in (:codes)""")
                    .param("codes", List.copyOf(unique.keySet()))
                    .query((rs, n) -> rs.getString(1)).list());
            List<String> unknown = unique.keySet().stream().filter(c -> !active.contains(c)).sorted().toList();
            if (!unknown.isEmpty()) {
                throw ApiException.unprocessable("unknown-permission",
                        "Unknown or inactive permission(s): " + unknown, Map.of("permissions", unknown));
            }
        }
        return unique.entrySet().stream().map(e -> new Grant(e.getKey(), e.getValue())).collect(Collectors.toList());
    }

    private Role load(UUID roleId) {
        UUID tenant = TenantContext.current().tenantId();
        return roles.findByTenantIdAndId(tenant, roleId).orElseThrow(() -> ApiException.notFound("Role", roleId));
    }

    private static void checkVersion(Role role, int expectedVersion) {
        if (role.getVersion() != expectedVersion) {
            throw ApiException.conflict("stale-version",
                    "Role " + role.getCode() + " was changed (version " + role.getVersion() + "); reload and retry");
        }
    }

    private static RoleDetail detail(Role role, List<RolePermission> grants) {
        List<Grant> sorted = grants.stream().map(rp -> new Grant(rp.getPermissionCode(), rp.getFieldMaskLevel()))
                .sorted((a, b) -> a.code().compareTo(b.code())).toList();
        return new RoleDetail(role.getId(), role.getCode(), role.getNameVi(), role.getNameEn(), role.isSystem(),
                role.getTemplateVersion(), role.getVersion() == null ? 0 : role.getVersion(), sorted);
    }
}
