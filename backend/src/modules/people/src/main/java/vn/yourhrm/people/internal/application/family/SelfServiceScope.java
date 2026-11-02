package vn.yourhrm.people.internal.application.family;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The current ESS user's own person and employments (EP-PPL-10, BR-PPL-120): taken from the SELF scope of the ESS
 * permission (Core's user ↔ employment link), never from a request parameter. Used by the ESS family / dependant and
 * bank pages.
 */
@Component
@HrTransactional(readOnly = true)
public class SelfServiceScope {

    /** The caller as an employee: their person and the employments their ESS grant covers. */
    public record Self(UUID personId, List<UUID> employmentIds) {
    }

    private final HrDb db;
    private final AuthorizationService authz;
    private final BusinessClock clock;

    public SelfServiceScope(HrDb db, AuthorizationService authz, BusinessClock clock) {
        this.db = db;
        this.authz = authz;
        this.clock = clock;
    }

    /** 404 when the user is not linked to an employment (no SELF scope of {@code permission}). */
    public Self require(String permission) {
        Set<UUID> employments = authz.scopeFor(permission, clock.today()).employmentIds();
        if (employments.isEmpty()) {
            throw ApiException.notFound("Employee record of the current user", "me");
        }
        List<UUID> persons = db.jdbc().sql("""
                        select distinct person_id from hr.employment where tenant_id = :t and id in (:ids)""")
                .param("t", TenantContext.current().tenantId()).param("ids", List.copyOf(employments))
                .query(UUID.class).list();
        if (persons.size() != 1) {
            throw ApiException.notFound("Employee record of the current user", "me");
        }
        return new Self(persons.getFirst(), List.copyOf(employments));
    }
}
