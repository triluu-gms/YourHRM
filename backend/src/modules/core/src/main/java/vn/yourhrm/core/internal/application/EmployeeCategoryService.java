package vn.yourhrm.core.internal.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CoreEvents.JobCatalogueChanged;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Employee categories (BR-CORE-026, US-CORE-023): the tenant's code list (chính thức, thử việc, thời vụ, thực tập,
 * người nước ngoài, CTV ...) with typed behaviour flags. Workflow conditions, data scope rules and payroll
 * eligibility key on the flags, not on the tenant-chosen code. Never deleted; deactivated instead.
 */
@Service
public class EmployeeCategoryService {

    private record Default(String code, String nameVi, String nameEn, Flags flags) {
    }

    /** Defaults seeded at provisioning. Flags: probation, seasonal, intern, expat, collaborator, counts in headcount. */
    private static final List<Default> DEFAULTS = List.of(
            new Default("CHINH_THUC", "Chính thức", "Permanent", new Flags(false, false, false, false, false, true)),
            new Default("THU_VIEC", "Thử việc", "Probation", new Flags(true, false, false, false, false, true)),
            new Default("THOI_VU", "Thời vụ", "Seasonal", new Flags(false, true, false, false, false, true)),
            new Default("THUC_TAP", "Thực tập", "Intern", new Flags(false, false, true, false, false, false)),
            new Default("NUOC_NGOAI", "Người nước ngoài", "Expatriate", new Flags(false, false, false, true, false, true)),
            new Default("CTV", "Cộng tác viên", "Collaborator", new Flags(false, false, false, false, true, false)));

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public EmployeeCategoryService(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Behaviour flags (DDL {@code core.employee_category}). */
    public record Flags(boolean probation, boolean seasonal, boolean intern, boolean expat, boolean collaborator,
            boolean countsInHeadcount) {
    }

    public record EmployeeCategoryView(UUID id, String code, String nameVi, String nameEn, Flags flags, int sortOrder,
            boolean active, int version) {
    }

    /** Idempotently creates the default categories for the current tenant (US-CORE-023 AC: pre-seeded). */
    @Transactional
    public void seedDefaults() {
        int order = 0;
        for (Default d : DEFAULTS) {
            order += 10;
            insert(d.code(), d.nameVi(), d.nameEn(), d.flags(), order, true);
        }
    }

    @Transactional(readOnly = true)
    public List<EmployeeCategoryView> list(boolean includeInactive) {
        return jdbc.sql("""
                        select * from core.employee_category where tenant_id = :t and (:all or is_active)
                         order by sort_order, code""")
                .param("t", TenantContext.current().tenantId()).param("all", includeInactive)
                .query((rs, n) -> map(rs)).list();
    }

    @Transactional
    public EmployeeCategoryView create(String code, String nameVi, String nameEn, Flags flags, Integer sortOrder) {
        if (insert(code, nameVi, nameEn, flags, sortOrder == null ? 0 : sortOrder, false) == null) {
            throw ApiException.conflict("employee-category-code-taken",
                    "An employee category with code " + code + " already exists");
        }
        return list(true).stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    @Transactional
    public EmployeeCategoryView update(UUID id, String nameVi, String nameEn, Flags f, int sortOrder, boolean active,
            int expectedVersion) {
        UUID tenant = TenantContext.current().tenantId();
        EmployeeCategoryView before = list(true).stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Employee category", id));
        int updated = jdbc.sql("""
                        update core.employee_category
                           set name_vi = :vi, name_en = :en, is_probation = :p, is_seasonal = :s, is_intern = :i,
                               is_expat = :x, is_collaborator = :c, counts_in_headcount = :h, sort_order = :o,
                               is_active = :a, updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("vi", nameVi).param("en", nameEn).param("p", f.probation()).param("s", f.seasonal())
                .param("i", f.intern()).param("x", f.expat()).param("c", f.collaborator())
                .param("h", f.countsInHeadcount()).param("o", sortOrder).param("a", active)
                .param("actor", TenantContext.current().userAccountId())
                .param("t", tenant).param("id", id).param("v", expectedVersion)
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale-version", "Employee category " + before.code() + " was changed (version "
                    + before.version() + "); reload and retry");
        }
        events.publishEvent(new JobCatalogueChanged(tenant, "EMPLOYEE_CATEGORY", id, "UPDATED"));
        return list(true).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Inserts unless the code exists; returns the new id or null. */
    private UUID insert(String code, String nameVi, String nameEn, Flags f, int sortOrder, boolean seeding) {
        UUID tenant = TenantContext.current().tenantId();
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                        insert into core.employee_category (tenant_id, id, code, name_vi, name_en, is_probation,
                               is_seasonal, is_intern, is_expat, is_collaborator, counts_in_headcount, sort_order,
                               created_by, updated_by)
                        values (:t, :id, :code, :vi, :en, :p, :s, :i, :x, :c, :h, :o, :actor, :actor)
                        on conflict (tenant_id, code) do nothing""")
                .param("t", tenant).param("id", id).param("code", code).param("vi", nameVi).param("en", nameEn)
                .param("p", f.probation()).param("s", f.seasonal()).param("i", f.intern()).param("x", f.expat())
                .param("c", f.collaborator()).param("h", f.countsInHeadcount()).param("o", sortOrder)
                .param("actor", TenantContext.current().userAccountId())
                .update();
        if (inserted == 0) {
            return null;
        }
        if (!seeding) {
            events.publishEvent(new JobCatalogueChanged(tenant, "EMPLOYEE_CATEGORY", id, "CREATED"));
        }
        return id;
    }

    private static EmployeeCategoryView map(ResultSet rs) throws SQLException {
        return new EmployeeCategoryView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name_vi"),
                rs.getString("name_en"),
                new Flags(rs.getBoolean("is_probation"), rs.getBoolean("is_seasonal"), rs.getBoolean("is_intern"),
                        rs.getBoolean("is_expat"), rs.getBoolean("is_collaborator"),
                        rs.getBoolean("counts_in_headcount")),
                rs.getInt("sort_order"), rs.getBoolean("is_active"), rs.getInt("version"));
    }
}
