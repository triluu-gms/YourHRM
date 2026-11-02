package vn.yourhrm.people.internal.infra.assignment;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.spi.ReportingLineProvider;

/**
 * People's implementation of Core's reporting-line SPI (US-CORE-036, integration "SPI implemented by People"): the
 * reporting line is {@code hr.assignment.manager_employment_id} of the current-knowledge assignment versions valid on
 * {@code asOf} (any assignment type, so a temporary-transfer manager also sees the employee). Core's
 * {@code ScopeResolver} resolves DIRECT_REPORTS / ALL_REPORTS grants with it. Runs its SQL in a read-only People
 * transaction on People's pool (Core calls it from inside its own transaction).
 */
@Component
@HrTransactional(readOnly = true)
public class PeopleReportingLines implements ReportingLineProvider {

    private final HrDb db;

    public PeopleReportingLines(HrDb db) {
        this.db = db;
    }

    @Override
    public Set<UUID> directReports(UUID tenantId, UUID managerEmploymentId, LocalDate asOf) {
        return new LinkedHashSet<>(db.jdbc().sql("""
                        select distinct a.employment_id from hr.assignment a
                          join hr.employment e on e.tenant_id = a.tenant_id and e.id = a.employment_id
                         where a.tenant_id = :t and a.manager_employment_id = :m and a.superseded_at is null
                           and a.valid_during @> cast(:d as date) and a.employment_id <> :m
                           and e.status <> 'CANCELLED'""")
                .param("t", tenantId).param("m", managerEmploymentId).param("d", asOf)
                .query(UUID.class).list());
    }

    @Override
    public Set<UUID> allReports(UUID tenantId, UUID managerEmploymentId, LocalDate asOf) {
        // UNION (not UNION ALL) stops on cycles in bad data.
        return new LinkedHashSet<>(db.jdbc().sql("""
                        with recursive reports(employment_id) as (
                            select a.employment_id from hr.assignment a
                             where a.tenant_id = :t and a.manager_employment_id = :m and a.superseded_at is null
                               and a.valid_during @> cast(:d as date)
                            union
                            select a.employment_id from hr.assignment a
                              join reports r on a.manager_employment_id = r.employment_id
                             where a.tenant_id = :t and a.superseded_at is null and a.valid_during @> cast(:d as date))
                        select r.employment_id from reports r
                          join hr.employment e on e.tenant_id = :t and e.id = r.employment_id
                         where r.employment_id <> :m and e.status <> 'CANCELLED'""")
                .param("t", tenantId).param("m", managerEmploymentId).param("d", asOf)
                .query(UUID.class).list());
    }
}
