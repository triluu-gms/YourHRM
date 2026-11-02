package vn.yourhrm.people.internal.application.family;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentOwnerResolver;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Access to dependant evidence follows the employee the family member belongs to (BR-CORE-101): the target is the
 * person's current employment (the PIT primary designation today, else the most recent live employment), so HR scope
 * and the employee's SELF scope both apply.
 */
@Component
@HrTransactional(readOnly = true)
public class FamilyDocumentOwners implements DocumentOwnerResolver {

    private final HrDb db;
    private final EmployeeAccess access;
    private final BusinessClock clock;

    public FamilyDocumentOwners(HrDb db, EmployeeAccess access, BusinessClock clock) {
        this.db = db;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public Set<String> ownerEntityTypes() {
        return Set.of(FamilyCatalogues.OWNER_FAMILY_MEMBER);
    }

    @Override
    public Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId) {
        var today = clock.today();
        Optional<UUID> employment = db.jdbc().sql("""
                        select e.id
                          from hr.family_member f
                          join hr.employment e on e.tenant_id = f.tenant_id and e.person_id = f.person_id
                          left join hr.primary_employment pe on pe.tenant_id = e.tenant_id and pe.employment_id = e.id
                               and pe.status = 'ACTIVE' and pe.valid_during @> cast(:d as date)
                         where f.tenant_id = :t and f.id = :id
                         order by (pe.id is not null) desc, (e.status in ('PRE_HIRE','ACTIVE','SUSPENDED')) desc,
                                  e.hire_date desc
                         limit 1""")
                .param("t", TenantContext.current().tenantId()).param("id", ownerEntityId).param("d", today)
                .query(UUID.class).optional();
        return employment.flatMap(e -> access.target(e, today)).map(AuthorizationTarget.class::cast);
    }
}
