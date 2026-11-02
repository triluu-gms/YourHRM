package vn.yourhrm.core.internal.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Grade catalogue (BR-CORE-021, US-CORE-021): grade structures (ngạch) and their grades (bậc / cấp bậc) with a rank
 * order that is unique within a structure at any date. Grades are effective-dated ({@code valid_from}/{@code valid_to},
 * DDL {@code grade_rank_ex}). Core stores NO pay amounts: pay scales and salary bands per grade are Payroll's
 * (IF-PAY-P-02), so People / Recruitment validate salaries against Payroll, not Core.
 */
@Service
public class GradeService {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;

    public GradeService(JdbcClient jdbc, ApplicationEventPublisher events, BusinessClock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    public record GradeStructureView(UUID id, String code, String name) {
    }

    public record GradeView(UUID id, UUID gradeStructureId, String code, String name, int rankOrder, boolean active,
            LocalDate validFrom, LocalDate validTo) {
    }

    // ------------------------------------------------------------------------------------ structures

    @Transactional(readOnly = true)
    public List<GradeStructureView> structures() {
        return jdbc.sql("select * from core.grade_structure where tenant_id = :t order by code")
                .param("t", tenant())
                .query((rs, n) -> new GradeStructureView(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name")))
                .list();
    }

    @Transactional
    public GradeStructureView createStructure(String code, String name) {
        UUID tenant = tenant();
        if (jdbc.sql("select exists (select 1 from core.grade_structure where tenant_id = :t and code = :c)")
                .param("t", tenant).param("c", code).query(Boolean.class).single()) {
            throw ApiException.conflict("grade-structure-code-taken", "A grade structure with code " + code + " exists");
        }
        UUID id = Ids.newId();
        jdbc.sql("insert into core.grade_structure (tenant_id, id, code, name) values (:t, :id, :c, :n)")
                .param("t", tenant).param("id", id).param("c", code).param("n", name).update();
        events.publishEvent(new JobCatalogueChanged(tenant, "GRADE_STRUCTURE", id, "CREATED"));
        return new GradeStructureView(id, code, name);
    }

    @Transactional
    public GradeStructureView renameStructure(UUID id, String name) {
        UUID tenant = tenant();
        if (jdbc.sql("update core.grade_structure set name = :n where tenant_id = :t and id = :id")
                .param("n", name).param("t", tenant).param("id", id).update() == 0) {
            throw ApiException.notFound("Grade structure", id);
        }
        events.publishEvent(new JobCatalogueChanged(tenant, "GRADE_STRUCTURE", id, "UPDATED"));
        return structures().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------ grades

    /** Grades of a structure in rank order, valid on {@code asOf} (all dates when {@code allDates}). */
    @Transactional(readOnly = true)
    public List<GradeView> grades(UUID structureId, LocalDate asOf, boolean allDates) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        return jdbc.sql("""
                        select * from core.grade
                         where tenant_id = :t and grade_structure_id = :s
                           and (:all or (is_active and valid_during @> cast(:d as date)))
                         order by rank_order, valid_from""")
                .param("t", tenant()).param("s", structureId).param("all", allDates).param("d", date)
                .query((rs, n) -> map(rs)).list();
    }

    @Transactional
    public GradeView createGrade(UUID structureId, String code, String name, int rankOrder, LocalDate validFrom,
            LocalDate validTo) {
        UUID tenant = tenant();
        if (!jdbc.sql("select exists (select 1 from core.grade_structure where tenant_id = :t and id = :s)")
                .param("t", tenant).param("s", structureId).query(Boolean.class).single()) {
            throw ApiException.notFound("Grade structure", structureId);
        }
        if (jdbc.sql("select exists (select 1 from core.grade where tenant_id = :t and grade_structure_id = :s and code = :c)")
                .param("t", tenant).param("s", structureId).param("c", code).query(Boolean.class).single()) {
            throw ApiException.conflict("grade-code-taken", "Grade " + code + " already exists in this structure");
        }
        LocalDate from = validFrom == null ? clock.today() : validFrom;
        UUID id = Ids.newId();
        assertRankFree(tenant, structureId, id, rankOrder, from, validTo);
        jdbc.sql("""
                        insert into core.grade (tenant_id, id, grade_structure_id, code, name, rank_order, valid_from, valid_to)
                        values (:t, :id, :s, :c, :n, :r, :from, :to)""")
                .param("t", tenant).param("id", id).param("s", structureId).param("c", code).param("n", name)
                .param("r", rankOrder).param("from", from).param("to", validTo)
                .update();
        events.publishEvent(new JobCatalogueChanged(tenant, "GRADE", id, "CREATED"));
        return grade(id);
    }

    /** Renames, re-ranks, ends ({@code validTo}) or (de)activates a grade; the code is immutable. */
    @Transactional
    public GradeView updateGrade(UUID id, String name, int rankOrder, LocalDate validTo, boolean active) {
        UUID tenant = tenant();
        GradeView g = grade(id);
        if (validTo != null && !validTo.isAfter(g.validFrom())) {
            throw ApiException.unprocessable("invalid-period", "The end date must be after " + g.validFrom());
        }
        assertRankFree(tenant, g.gradeStructureId(), id, rankOrder, g.validFrom(), validTo);
        jdbc.sql("""
                        update core.grade set name = :n, rank_order = :r, valid_to = :to, is_active = :a
                         where tenant_id = :t and id = :id""")
                .param("n", name).param("r", rankOrder).param("to", validTo).param("a", active)
                .param("t", tenant).param("id", id).update();
        events.publishEvent(new JobCatalogueChanged(tenant, "GRADE", id, "UPDATED"));
        return grade(id);
    }

    /** Friendly pre-check of the DDL exclusion constraint {@code grade_rank_ex}. */
    private void assertRankFree(UUID tenant, UUID structureId, UUID gradeId, int rankOrder, LocalDate from,
            LocalDate to) {
        String clash = jdbc.sql("""
                        select code from core.grade
                         where tenant_id = :t and grade_structure_id = :s and rank_order = :r and id <> :id
                           and valid_during && daterange(cast(:from as date), cast(:to as date), '[)')
                         limit 1""")
                .param("t", tenant).param("s", structureId).param("r", rankOrder).param("id", gradeId)
                .param("from", from).param("to", to)
                .query(String.class).optional().orElse(null);
        if (clash != null) {
            throw ApiException.conflict("grade-rank-taken",
                    "Rank " + rankOrder + " is already used by grade " + clash + " in an overlapping period");
        }
    }

    private GradeView grade(UUID id) {
        return jdbc.sql("select * from core.grade where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Grade", id));
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static GradeView map(ResultSet rs) throws SQLException {
        return new GradeView(rs.getObject("id", UUID.class), rs.getObject("grade_structure_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getInt("rank_order"), rs.getBoolean("is_active"),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class));
    }
}
