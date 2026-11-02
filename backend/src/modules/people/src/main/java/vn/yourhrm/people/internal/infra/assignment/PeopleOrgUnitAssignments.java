package vn.yourhrm.people.internal.infra.assignment;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.OrgUnitAssignments;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * People's implementation of Core's {@link OrgUnitAssignments} SPI (C-10, US-CORE-012): is anybody assigned to an org
 * unit on or after a date? Core asks before closing a unit. Current knowledge, any assignment type, employments that
 * are not cancelled. Runs in a read-only People transaction for the current tenant.
 */
@Component
@HrTransactional(readOnly = true)
public class PeopleOrgUnitAssignments implements OrgUnitAssignments {

    private final HrDb db;

    public PeopleOrgUnitAssignments(HrDb db) {
        this.db = db;
    }

    @Override
    public boolean hasActiveAssignments(UUID orgUnitId, LocalDate from) {
        return db.jdbc().sql("""
                        select exists (
                          select 1 from hr.assignment a
                            join hr.employment e on e.tenant_id = a.tenant_id and e.id = a.employment_id
                           where a.tenant_id = :t and a.org_unit_id = :ou and a.superseded_at is null
                             and (a.valid_to is null or a.valid_to > :d)
                             and e.status <> 'CANCELLED' and (e.end_date is null or e.end_date >= :d))""")
                .param("t", TenantContext.current().tenantId()).param("ou", orgUnitId).param("d", from)
                .query(Boolean.class).single();
    }
}
