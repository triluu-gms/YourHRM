package vn.yourhrm.core.internal.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
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
 * Job families and job titles (BR-CORE-020, US-CORE-020). A job title carries the JD (document service), a default
 * grade range, the national occupation code and the statutory heavy / hazardous / dangerous category, which People,
 * T&A and Payroll key on (leave, OT, retirement, SI) [verify-legal]. The category must be a value of the vendor code
 * list {@value #HAZARD_LIST} (TT 11/2020/TT-BLĐTBXH and amendments); null = not hazardous. Codes are immutable;
 * entries are deactivated, never deleted.
 */
@Service
public class JobTitleService {

    /** Vendor code list of statutory heavy / hazardous job categories [verify-legal]. */
    public static final String HAZARD_LIST = "JOB_HAZARD_CATEGORY";

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public JobTitleService(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public record JobFamilyView(UUID id, String code, String nameVi, String nameEn, boolean active) {
    }

    public record JobTitleAttributes(UUID jobFamilyId, String nameVi, String nameEn, String description,
            UUID jdDocumentId, UUID defaultGradeMinId, UUID defaultGradeMaxId, String occupationCode,
            String hazardousCategory, boolean active) {
    }

    public record JobTitleView(UUID id, String code, UUID jobFamilyId, String nameVi, String nameEn,
            String description, UUID jdDocumentId, UUID defaultGradeMinId, UUID defaultGradeMaxId,
            String occupationCode, String hazardousCategory, boolean hazardous, boolean active, int version) {
    }

    // ------------------------------------------------------------------------------------ job families

    @Transactional(readOnly = true)
    public List<JobFamilyView> families(boolean includeInactive) {
        return jdbc.sql("select * from core.job_family where tenant_id = :t and (:all or is_active) order by code")
                .param("t", tenant()).param("all", includeInactive)
                .query((rs, n) -> new JobFamilyView(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name_vi"), rs.getString("name_en"), rs.getBoolean("is_active")))
                .list();
    }

    @Transactional
    public JobFamilyView createFamily(String code, String nameVi, String nameEn) {
        UUID tenant = tenant();
        if (exists("select 1 from core.job_family where tenant_id = :t and code = :c", tenant, code)) {
            throw ApiException.conflict("job-family-code-taken", "A job family with code " + code + " already exists");
        }
        UUID id = Ids.newId();
        jdbc.sql("insert into core.job_family (tenant_id, id, code, name_vi, name_en) values (:t, :id, :c, :vi, :en)")
                .param("t", tenant).param("id", id).param("c", code).param("vi", nameVi).param("en", nameEn).update();
        events.publishEvent(new JobCatalogueChanged(tenant, "JOB_FAMILY", id, "CREATED"));
        return family(id);
    }

    @Transactional
    public JobFamilyView updateFamily(UUID id, String nameVi, String nameEn, boolean active) {
        UUID tenant = tenant();
        int updated = jdbc.sql("""
                        update core.job_family set name_vi = :vi, name_en = :en, is_active = :a
                         where tenant_id = :t and id = :id""")
                .param("vi", nameVi).param("en", nameEn).param("a", active).param("t", tenant).param("id", id).update();
        if (updated == 0) {
            throw ApiException.notFound("Job family", id);
        }
        events.publishEvent(new JobCatalogueChanged(tenant, "JOB_FAMILY", id, "UPDATED"));
        return family(id);
    }

    private JobFamilyView family(UUID id) {
        return families(true).stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Job family", id));
    }

    // ------------------------------------------------------------------------------------ job titles

    @Transactional(readOnly = true)
    public List<JobTitleView> titles(String query, UUID familyId, boolean includeInactive) {
        String q = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        return jdbc.sql("""
                        select * from core.job_title
                         where tenant_id = :t and (:all or is_active)
                           and (cast(:f as uuid) is null or job_family_id = :f)
                           and (cast(:q as text) is null or lower(code) like :q or lower(name_vi) like :q
                                or lower(coalesce(name_en, '')) like :q)
                         order by code""")
                .param("t", tenant()).param("all", includeInactive).param("f", familyId).param("q", q)
                .query((rs, n) -> mapTitle(rs)).list();
    }

    @Transactional(readOnly = true)
    public JobTitleView title(UUID id) {
        return jdbc.sql("select * from core.job_title where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query((rs, n) -> mapTitle(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Job title", id));
    }

    @Transactional
    public JobTitleView createTitle(String code, JobTitleAttributes a) {
        UUID tenant = tenant();
        if (exists("select 1 from core.job_title where tenant_id = :t and code = :c", tenant, code)) {
            throw ApiException.conflict("job-title-code-taken", "A job title with code " + code + " already exists");
        }
        validate(tenant, a);
        UUID id = Ids.newId();
        jdbc.sql("""
                        insert into core.job_title (tenant_id, id, code, job_family_id, name_vi, name_en, description,
                               jd_document_id, default_grade_min_id, default_grade_max_id, occupation_code,
                               hazardous_category, is_active, created_by, updated_by)
                        values (:t, :id, :code, :fam, :vi, :en, :descr, :jd, :gmin, :gmax, :occ, :haz, :active,
                                :actor, :actor)""")
                .param("t", tenant).param("id", id).param("code", code).param("fam", a.jobFamilyId())
                .param("vi", a.nameVi()).param("en", a.nameEn()).param("descr", a.description())
                .param("jd", a.jdDocumentId()).param("gmin", a.defaultGradeMinId()).param("gmax", a.defaultGradeMaxId())
                .param("occ", a.occupationCode()).param("haz", a.hazardousCategory()).param("active", a.active())
                .param("actor", TenantContext.current().userAccountId())
                .update();
        events.publishEvent(new JobCatalogueChanged(tenant, "JOB_TITLE", id, "CREATED"));
        return title(id);
    }

    @Transactional
    public JobTitleView updateTitle(UUID id, JobTitleAttributes a, int expectedVersion) {
        UUID tenant = tenant();
        JobTitleView before = title(id);
        validate(tenant, a);
        int updated = jdbc.sql("""
                        update core.job_title
                           set job_family_id = :fam, name_vi = :vi, name_en = :en, description = :descr,
                               jd_document_id = :jd, default_grade_min_id = :gmin, default_grade_max_id = :gmax,
                               occupation_code = :occ, hazardous_category = :haz, is_active = :active,
                               updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("fam", a.jobFamilyId()).param("vi", a.nameVi()).param("en", a.nameEn())
                .param("descr", a.description()).param("jd", a.jdDocumentId()).param("gmin", a.defaultGradeMinId())
                .param("gmax", a.defaultGradeMaxId()).param("occ", a.occupationCode())
                .param("haz", a.hazardousCategory()).param("active", a.active())
                .param("actor", TenantContext.current().userAccountId())
                .param("t", tenant).param("id", id).param("v", expectedVersion)
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale-version", "Job title " + before.code() + " was changed (version "
                    + before.version() + "); reload and retry");
        }
        // People / T&A / Payroll re-evaluate hazardous-job rules on this signal (US-CORE-020 AC).
        String change = Objects.equals(before.hazardousCategory(), a.hazardousCategory()) ? "UPDATED" : "HAZARD_CHANGED";
        events.publishEvent(new JobCatalogueChanged(tenant, "JOB_TITLE", id, change));
        return title(id);
    }

    private void validate(UUID tenant, JobTitleAttributes a) {
        if (a.jobFamilyId() != null && !exists("select 1 from core.job_family where tenant_id = :t and id = cast(:c as uuid)"
                + " and is_active", tenant, a.jobFamilyId().toString())) {
            throw ApiException.unprocessable("invalid-job-family", "Job family " + a.jobFamilyId() + " is not active");
        }
        if (a.hazardousCategory() != null && !exists("""
                        select 1 from core.v_code_value_effective
                         where list_code = 'JOB_HAZARD_CATEGORY' and value_code = :c and is_active
                           and tenant_id in (:t, sys.global_tenant_id())""", tenant, a.hazardousCategory())) {
            throw ApiException.unprocessable("unknown-hazardous-category",
                    "Hazardous category " + a.hazardousCategory() + " is not in the statutory list " + HAZARD_LIST);
        }
        if (a.jdDocumentId() != null && !exists("select 1 from core.document where tenant_id = :t"
                + " and id = cast(:c as uuid) and status = 'ACTIVE'", tenant, a.jdDocumentId().toString())) {
            throw ApiException.unprocessable("unknown-document", "JD document " + a.jdDocumentId() + " not found");
        }
        if ((a.defaultGradeMinId() == null) != (a.defaultGradeMaxId() == null)) {
            throw ApiException.unprocessable("invalid-grade-range", "Give both ends of the default grade range or neither");
        }
        if (a.defaultGradeMinId() != null) {
            // Both grades in the same structure and min ranks at or below max.
            boolean ok = jdbc.sql("""
                            select exists (select 1 from core.grade lo join core.grade hi
                                             on hi.tenant_id = lo.tenant_id and hi.grade_structure_id = lo.grade_structure_id
                                          where lo.tenant_id = :t and lo.id = :lo and hi.id = :hi
                                            and lo.rank_order <= hi.rank_order and lo.is_active and hi.is_active)""")
                    .param("t", tenant).param("lo", a.defaultGradeMinId()).param("hi", a.defaultGradeMaxId())
                    .query(Boolean.class).single();
            if (!ok) {
                throw ApiException.unprocessable("invalid-grade-range",
                        "The default grade range must be two active grades of one structure, lowest first");
            }
        }
    }

    private boolean exists(String sql, UUID tenant, String value) {
        return jdbc.sql("select exists (" + sql + ")").param("t", tenant).param("c", value).query(Boolean.class).single();
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static JobTitleView mapTitle(ResultSet rs) throws SQLException {
        String hazard = rs.getString("hazardous_category");
        return new JobTitleView(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getObject("job_family_id", UUID.class), rs.getString("name_vi"), rs.getString("name_en"),
                rs.getString("description"), rs.getObject("jd_document_id", UUID.class),
                rs.getObject("default_grade_min_id", UUID.class), rs.getObject("default_grade_max_id", UUID.class),
                rs.getString("occupation_code"), hazard, hazard != null, rs.getBoolean("is_active"), rs.getInt("version"));
    }
}
