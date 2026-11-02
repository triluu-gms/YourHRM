package vn.yourhrm.core.internal.application;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Configurable org unit types (BR-CORE-010, US-CORE-010): the tenant's own vocabulary for tree levels. Types are
 * never deleted (versions reference them); a deactivated type cannot be used by new versions.
 */
@Service
public class OrgUnitTypeService {

    /** Defaults seeded at provisioning (code, VI, EN, level hint). */
    private static final List<String[]> DEFAULTS = List.of(
            new String[] {"COMPANY", "Công ty", "Company", "1"},
            new String[] {"DIVISION", "Khối", "Division", "2"},
            new String[] {"DEPARTMENT", "Phòng", "Department", "3"},
            new String[] {"BOARD", "Ban", "Board", "3"},
            new String[] {"SECTION", "Bộ phận", "Section", "4"},
            new String[] {"TEAM", "Tổ/Nhóm", "Team", "5"});

    private final JdbcClient jdbc;

    public OrgUnitTypeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record OrgUnitTypeView(UUID id, String code, String nameVi, String nameEn, Integer levelHint, int sortOrder,
            boolean active) {
    }

    /** Idempotently creates the default types for the current tenant (provisioning, US-CORE-010 AC). */
    @Transactional
    public void seedDefaults() {
        UUID tenant = TenantContext.current().tenantId();
        int order = 0;
        for (String[] d : DEFAULTS) {
            order += 10;
            jdbc.sql("""
                            insert into core.org_unit_type (tenant_id, id, code, name_vi, name_en, level_hint, sort_order)
                            values (:t, :id, :code, :vi, :en, :level, :order)
                            on conflict (tenant_id, code) do nothing""")
                    .param("t", tenant).param("id", Ids.newId()).param("code", d[0]).param("vi", d[1])
                    .param("en", d[2]).param("level", Short.valueOf(d[3])).param("order", order)
                    .update();
        }
    }

    @Transactional(readOnly = true)
    public List<OrgUnitTypeView> list(boolean includeInactive) {
        return jdbc.sql("""
                        select * from core.org_unit_type where tenant_id = :t and (:all or is_active)
                         order by sort_order, code""")
                .param("t", TenantContext.current().tenantId()).param("all", includeInactive)
                .query((rs, n) -> new OrgUnitTypeView(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name_vi"), rs.getString("name_en"), rs.getObject("level_hint",
                                Integer.class), rs.getInt("sort_order"), rs.getBoolean("is_active")))
                .list();
    }

    @Transactional
    public OrgUnitTypeView create(String code, String nameVi, String nameEn, Integer levelHint, Integer sortOrder) {
        UUID tenant = TenantContext.current().tenantId();
        if (jdbc.sql("select exists (select 1 from core.org_unit_type where tenant_id = :t and code = :c)")
                .param("t", tenant).param("c", code).query(Boolean.class).single()) {
            throw ApiException.conflict("org-unit-type-code-taken", "An org unit type with code " + code + " exists");
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                        insert into core.org_unit_type (tenant_id, id, code, name_vi, name_en, level_hint, sort_order)
                        values (:t, :id, :code, :vi, :en, :level, :order)""")
                .param("t", tenant).param("id", id).param("code", code).param("vi", nameVi).param("en", nameEn)
                .param("level", levelHint == null ? null : levelHint.shortValue())
                .param("order", sortOrder == null ? 0 : sortOrder)
                .update();
        return get(id);
    }

    /** Renames, reorders or (de)activates a type; the code is immutable. */
    @Transactional
    public OrgUnitTypeView update(UUID id, String nameVi, String nameEn, Integer levelHint, int sortOrder,
            boolean active) {
        int updated = jdbc.sql("""
                        update core.org_unit_type
                           set name_vi = :vi, name_en = :en, level_hint = :level, sort_order = :order, is_active = :active
                         where tenant_id = :t and id = :id""")
                .param("vi", nameVi).param("en", nameEn).param("level", levelHint == null ? null : levelHint.shortValue())
                .param("order", sortOrder).param("active", active)
                .param("t", TenantContext.current().tenantId()).param("id", id)
                .update();
        if (updated == 0) {
            throw ApiException.notFound("Org unit type", id);
        }
        return get(id);
    }

    private OrgUnitTypeView get(UUID id) {
        return list(true).stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Org unit type", id));
    }
}
