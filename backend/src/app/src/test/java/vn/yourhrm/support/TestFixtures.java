package vn.yourhrm.support;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.yourhrm.shared.common.id.Ids;

/**
 * Builds tenant data directly in the database as superuser (setup only; the code under test always
 * connects as yhrm_app_core). Every test creates its own tenant(s), so tests never share data.
 */
public final class TestFixtures {

    public static final LocalDate EPOCH = LocalDate.of(2020, 1, 1);

    private final JdbcTemplate admin;

    public TestFixtures(JdbcTemplate admin) {
        this.admin = admin;
    }

    public record TestUser(UUID tenantId, UUID userId, UUID identityId, String login) {
    }

    public record OrgUnit(UUID id, String path) {
    }

    public UUID tenant(String slugPrefix) {
        UUID id = Ids.newId();
        String slug = (slugPrefix + "-" + id.toString().substring(24)).toLowerCase();
        admin.update("insert into saas.tenant (id, slug, name, state) values (?, ?, ?, 'ACTIVE')", id, slug,
                "Tenant " + slug);
        admin.update("insert into core.org_unit_type (tenant_id, id, code, name_vi, name_en) values (?, ?, 'UNIT', 'Đơn vị', 'Unit')",
                id, Ids.newId());
        return id;
    }

    public UUID legalEntity(UUID tenant, String code) {
        UUID id = Ids.newId();
        admin.update("insert into core.legal_entity (tenant_id, id, code) values (?, ?, ?)", tenant, id, code);
        admin.update("""
                insert into core.legal_entity_version (tenant_id, id, legal_entity_id, valid_from, legal_name_vi, tax_code)
                values (?, ?, ?, ?, ?, '0101234567')""", tenant, Ids.newId(), id, Date.valueOf(EPOCH), "Pháp nhân " + code);
        return id;
    }

    public UUID location(UUID tenant, UUID legalEntity, String code) {
        UUID id = Ids.newId();
        admin.update("insert into core.location (tenant_id, id, legal_entity_id, code) values (?, ?, ?, ?)",
                tenant, id, legalEntity, code);
        admin.update("""
                insert into core.location_version (tenant_id, id, location_id, valid_from, name, location_type)
                values (?, ?, ?, ?, ?, 'BRANCH')""", tenant, Ids.newId(), id, Date.valueOf(EPOCH), "Location " + code);
        return id;
    }

    /** Creates an org unit anchor plus its first version [validFrom, validTo) under {@code parent}. */
    public OrgUnit orgUnit(UUID tenant, String code, OrgUnit parent, UUID legalEntity, UUID location,
            LocalDate validFrom, LocalDate validTo) {
        UUID id = Ids.newId();
        admin.update("insert into core.org_unit (tenant_id, id, code) values (?, ?, ?)", tenant, id, code);
        return orgUnitVersion(tenant, id, parent, legalEntity, location, validFrom, validTo);
    }

    /** Adds a version of an existing unit (e.g. after a reorganisation). */
    public OrgUnit orgUnitVersion(UUID tenant, UUID unitId, OrgUnit parent, UUID legalEntity, UUID location,
            LocalDate validFrom, LocalDate validTo) {
        String label = unitId.toString().replace("-", "");
        String path = parent == null ? label : parent.path() + "." + label;
        UUID type = admin.queryForObject("select id from core.org_unit_type where tenant_id = ? and code = 'UNIT'",
                UUID.class, tenant);
        admin.update("""
                insert into core.org_unit_version (tenant_id, id, org_unit_id, valid_from, valid_to, name_vi,
                       org_unit_type_id, parent_id, path, legal_entity_id, location_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, text2ltree(?), ?, ?)""",
                tenant, Ids.newId(), unitId, Date.valueOf(validFrom), validTo == null ? null : Date.valueOf(validTo),
                "Unit " + label.substring(24), type, parent == null ? null : parent.id(), path, legalEntity, location);
        return new OrgUnit(unitId, path);
    }

    public UUID role(UUID tenant, String code, boolean system, String... permissions) {
        UUID id = Ids.newId();
        admin.update("insert into core.role (tenant_id, id, code, name_vi, name_en, is_system) values (?, ?, ?, ?, ?, ?)",
                tenant, id, code, "Vai trò " + code, "Role " + code, system);
        for (String p : permissions) {
            admin.update("insert into core.role_permission (tenant_id, role_id, permission_code) values (?, ?, ?)",
                    tenant, id, p);
        }
        return id;
    }

    /** All active core.* permission codes of the global catalogue. */
    public List<String> allCorePermissions() {
        return admin.queryForList("select permission_code from core.permission where owner_module = 'core' and is_active order by 1",
                String.class);
    }

    public TestUser user(UUID tenant, String login) {
        UUID id = Ids.newId();
        UUID identity = UUID.randomUUID();
        admin.update("insert into core.user_account (tenant_id, id, login, identity_id, status) values (?, ?, ?, ?, 'ACTIVE')",
                tenant, id, login, identity);
        return new TestUser(tenant, id, identity, login);
    }

    public UUID assign(TestUser user, UUID role, String scopeType, List<UUID> scopeIds, boolean subtree,
            LocalDate validFrom, LocalDate validTo) {
        UUID id = Ids.newId();
        admin.update("""
                insert into core.role_assignment (tenant_id, id, user_id, role_id, scope_type, scope_ids,
                       include_subtree, valid_from, valid_to)
                values (?, ?, ?, ?, ?, cast(? as uuid[]), ?, ?, ?)""",
                user.tenantId(), id, user.userId(), role, scopeType,
                "{" + String.join(",", scopeIds.stream().map(UUID::toString).toList()) + "}",
                subtree, Date.valueOf(validFrom), validTo == null ? null : Date.valueOf(validTo));
        return id;
    }

    /** A tenant owner: TENANT-scoped system role holding every core permission. */
    public TestUser owner(UUID tenant) {
        UUID role = role(tenant, "TENANT_OWNER", true, allCorePermissions().toArray(String[]::new));
        TestUser owner = user(tenant, "owner-" + tenant.toString().substring(24) + "@test.local");
        assign(owner, role, "TENANT", List.of(), true, EPOCH, null);
        return owner;
    }

    public static List<UUID> ids(UUID... ids) {
        return Arrays.asList(ids);
    }
}
