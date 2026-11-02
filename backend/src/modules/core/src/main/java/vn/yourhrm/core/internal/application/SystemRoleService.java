package vn.yourhrm.core.internal.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CoreEvents.RoleChanged;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.SystemRoles;
import vn.yourhrm.core.internal.domain.FieldMaskLevel;
import vn.yourhrm.core.internal.domain.SystemRoleCodes;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * System role templates (US-CORE-033). A template selects permissions from the global catalogue by rule, so a
 * module that registers new permissions is covered by the next {@link #syncSystemRoles()} without a template
 * change. System roles are read-only for tenants (US-CORE-034), so syncing may overwrite their matrix.
 * {@link #TEMPLATE_VERSION} is stamped on the role ({@code core.role.template_version}); bump it when a rule changes.
 */
@Service
public class SystemRoleService implements SystemRoles {

    public static final int TEMPLATE_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(SystemRoleService.class);

    /** A catalogue entry as seen by the template rules. */
    record Permission(String code, String module, String action) {
    }

    private record Template(String code, String nameVi, String nameEn, FieldMaskLevel mask,
            Predicate<Permission> includes) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template(SystemRoleCodes.TENANT_OWNER, "Chủ sở hữu tenant", "Tenant Owner", FieldMaskLevel.NONE,
                    p -> true),
            // HR administration of master data and users; role design and the company profile stay with the owner.
            new Template(SystemRoleCodes.HR_ADMIN, "Quản trị nhân sự", "HR Admin", FieldMaskLevel.NONE,
                    p -> !p.code().startsWith("core.role.") && !p.code().equals(CorePermissions.COMPANY_GROUP_UPDATE)
                            && !p.code().equals(CorePermissions.LEGAL_ENTITY_CREATE)
                            && !p.code().startsWith("core.support_session.")),
            // Employee self-service, scope SELF: the employer's profile and structure; modules add self-service
            // permissions of their own (action VIEW on the user's own records).
            new Template(SystemRoleCodes.ESS, "Nhân viên tự phục vụ", "Employee self-service", FieldMaskLevel.NONE,
                    p -> p.code().equals(CorePermissions.COMPANY_GROUP_VIEW)
                            || p.code().equals(CorePermissions.ORG_UNIT_VIEW)),
            new Template(SystemRoleCodes.LINE_MANAGER, "Quản lý trực tiếp", "Line manager", FieldMaskLevel.PII,
                    p -> p.code().equals(CorePermissions.ORG_UNIT_VIEW) || p.code().equals(CorePermissions.LOCATION_VIEW)
                            || p.code().equals(CorePermissions.JOB_CATALOGUE_VIEW)),
            // Former employees keep read access to their own documents (payslips are registered by Payroll).
            new Template(SystemRoleCodes.FORMER_EMPLOYEE, "Nhân viên đã nghỉ việc", "Former employee",
                    FieldMaskLevel.NONE, p -> p.code().equals(CorePermissions.COMPANY_GROUP_VIEW)),
            // Read-only everywhere, sensitive and pay data masked.
            new Template(SystemRoleCodes.AUDITOR, "Kiểm toán viên", "Auditor", FieldMaskLevel.PII_AND_PAY,
                    p -> "VIEW".equals(p.action())));

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public SystemRoleService(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public int syncSystemRoles() {
        UUID tenant = TenantContext.current().tenantId();
        List<Permission> catalogue = jdbc.sql("""
                        select permission_code, owner_module, action from core.permission where is_active
                         order by permission_code""")
                .query((rs, n) -> new Permission(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        int changed = 0;
        for (Template t : TEMPLATES) {
            Map<String, FieldMaskLevel> wanted = new LinkedHashMap<>();
            catalogue.stream().filter(t.includes()).forEach(p -> wanted.put(p.code(), t.mask()));
            if (sync(tenant, t, wanted)) {
                changed++;
            }
        }
        return changed;
    }

    /** Creates or updates one system role; returns true when anything changed. */
    private boolean sync(UUID tenant, Template t, Map<String, FieldMaskLevel> wanted) {
        record Existing(UUID id, boolean system) {
        }
        Optional<Existing> existing = jdbc.sql("select id, is_system from core.role where tenant_id = :t and code = :c")
                .param("t", tenant).param("c", t.code())
                .query((rs, n) -> new Existing(rs.getObject(1, UUID.class), rs.getBoolean(2))).optional();
        if (existing.isPresent() && !existing.get().system()) {
            log.warn("Tenant {} has a custom role {}; system role not seeded", tenant, t.code());
            return false;
        }
        UUID roleId;
        boolean changed = false;
        if (existing.isEmpty()) {
            roleId = Ids.newId();
            jdbc.sql("""
                            insert into core.role (tenant_id, id, code, name_vi, name_en, is_system, template_version)
                            values (:t, :id, :c, :vi, :en, true, :v)""")
                    .param("t", tenant).param("id", roleId).param("c", t.code()).param("vi", t.nameVi())
                    .param("en", t.nameEn()).param("v", TEMPLATE_VERSION).update();
            changed = true;
        } else {
            roleId = existing.get().id();
            changed = jdbc.sql("""
                            update core.role set template_version = :v, name_vi = :vi, name_en = :en
                             where tenant_id = :t and id = :id
                               and (template_version is distinct from :v or name_vi <> :vi or name_en <> :en)""")
                    .param("v", TEMPLATE_VERSION).param("vi", t.nameVi()).param("en", t.nameEn())
                    .param("t", tenant).param("id", roleId).update() > 0;
        }
        Map<String, String> current = new LinkedHashMap<>();
        jdbc.sql("select permission_code, field_mask_level from core.role_permission where tenant_id = :t and role_id = :r")
                .param("t", tenant).param("r", roleId)
                .query(rs -> {
                    current.put(rs.getString(1), rs.getString(2));
                });
        for (String code : current.keySet()) {
            if (!wanted.containsKey(code)) {
                jdbc.sql("delete from core.role_permission where tenant_id = :t and role_id = :r and permission_code = :p")
                        .param("t", tenant).param("r", roleId).param("p", code).update();
                changed = true;
            }
        }
        for (Map.Entry<String, FieldMaskLevel> w : wanted.entrySet()) {
            String mask = w.getValue().name();
            if (!current.containsKey(w.getKey())) {
                jdbc.sql("""
                                insert into core.role_permission (tenant_id, role_id, permission_code, field_mask_level)
                                values (:t, :r, :p, :m)""")
                        .param("t", tenant).param("r", roleId).param("p", w.getKey()).param("m", mask).update();
                changed = true;
            } else if (!mask.equals(current.get(w.getKey()))) {
                jdbc.sql("""
                                update core.role_permission set field_mask_level = :m
                                 where tenant_id = :t and role_id = :r and permission_code = :p""")
                        .param("m", mask).param("t", tenant).param("r", roleId).param("p", w.getKey()).update();
                changed = true;
            }
        }
        if (changed) {
            events.publishEvent(new RoleChanged(tenant, roleId, existing.isEmpty() ? "SYSTEM_SEEDED" : "SYSTEM_UPGRADED"));
        }
        return changed;
    }
}
