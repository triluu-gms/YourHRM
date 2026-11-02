package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.JobCatalogue;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/** {@link JobCatalogue} implementation: one query per entity type and call (US-CORE-024). */
@Service
public class JobCatalogueLookup implements JobCatalogue {

    private final JdbcClient jdbc;

    public JobCatalogueLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, JobTitle> jobTitles(Collection<UUID> ids) {
        return byId(ids, "select * from core.job_title where tenant_id = :t and id in (:ids)", null,
                (rs, n) -> new JobTitle(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name_vi"),
                        rs.getString("name_en"), rs.getObject("job_family_id", UUID.class),
                        rs.getString("occupation_code"), rs.getString("hazardous_category"),
                        rs.getBoolean("is_active")),
                JobTitle::id);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Grade> grades(Collection<UUID> ids, LocalDate asOf) {
        return byId(ids, "select * from core.grade where tenant_id = :t and id in (:ids) and is_active"
                        + " and valid_during @> cast(:d as date)", asOf,
                (rs, n) -> new Grade(rs.getObject("id", UUID.class), rs.getObject("grade_structure_id", UUID.class),
                        rs.getString("code"), rs.getString("name"), rs.getInt("rank_order")),
                Grade::id);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CostCentre> costCentres(Collection<UUID> ids, LocalDate asOf) {
        return byId(ids, "select * from core.cost_centre where tenant_id = :t and id in (:ids)"
                        + " and valid_from <= :d and (valid_to is null or valid_to > :d)", asOf,
                (rs, n) -> new CostCentre(rs.getObject("id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                        rs.getString("code"), rs.getString("name"), rs.getString("gl_segment")),
                CostCentre::id);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, EmployeeCategory> employeeCategories(Collection<UUID> ids) {
        return byId(ids, "select * from core.employee_category where tenant_id = :t and id in (:ids)", null,
                (rs, n) -> new EmployeeCategory(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name_vi"), rs.getBoolean("is_probation"), rs.getBoolean("is_seasonal"),
                        rs.getBoolean("is_intern"), rs.getBoolean("is_expat"), rs.getBoolean("is_collaborator"),
                        rs.getBoolean("counts_in_headcount"), rs.getBoolean("is_active")),
                EmployeeCategory::id);
    }

    private <T> Map<UUID, T> byId(Collection<UUID> ids, String sql, LocalDate asOf, RowMapper<T> mapper,
            Function<T, UUID> key) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var spec = jdbc.sql(sql).param("t", TenantContext.current().tenantId()).param("ids", List.copyOf(ids));
        if (asOf != null) {
            spec = spec.param("d", asOf);
        }
        return spec.query(mapper).list().stream().collect(Collectors.toUnmodifiableMap(key, Function.identity()));
    }
}
