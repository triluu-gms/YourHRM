package vn.yourhrm.people.internal.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Data-scope checks on People records (US-PPL-001, ADR-0006): an employment is in a user's scope through its legal
 * entity, the org unit / location of its PRIMARY assignment on the check date, or the employment itself (SELF,
 * DIRECT_REPORTS, ALL_REPORTS). Out of scope looks the same as missing (404, no existence oracle).
 *
 * <p>Queries over many employments join {@link #EMPLOYMENT_SCOPE_JOIN} and filter with {@link #scope}. Every method
 * reads {@code hr} in a People transaction (joining the caller's one when there is).
 */
@Component
@HrTransactional(readOnly = true)
public class EmployeeAccess {

    /** Columns of {@link #EMPLOYMENT_SCOPE_JOIN} for {@link ScopePredicate#toSql}. */
    public static final ScopePredicate.Columns COLUMNS =
            new ScopePredicate.Columns("e.legal_entity_id", "pa.org_unit_id", "pa.location_id", "e.id");

    /**
     * {@code hr.employment e} plus its PRIMARY assignment {@code pa} (current knowledge) valid on {@code :asOf}, else the
     * nearest one (a pre-hire's first line, a leaver's last line). Needs the parameter {@code asOf}.
     */
    public static final String EMPLOYMENT_SCOPE_JOIN = """
            hr.employment e
              left join lateral (
                    select x.org_unit_id, x.location_id, x.position_id, x.job_title_id, x.manager_employment_id
                      from hr.assignment x
                     where x.tenant_id = e.tenant_id and x.employment_id = e.id and x.assignment_type = 'PRIMARY'
                       and x.superseded_at is null
                     order by (x.valid_during @> cast(:asOf as date)) desc, x.valid_from desc
                     limit 1) pa on true""";

    private final HrDb db;
    private final AuthorizationService authz;

    public EmployeeAccess(HrDb db, AuthorizationService authz) {
        this.db = db;
        this.authz = authz;
    }

    /** The authorisation target of an employment on {@code asOf}; empty when it does not exist in the tenant. */
    public Optional<AuthorizationTarget.Employee> target(UUID employmentId, LocalDate asOf) {
        return db.jdbc().sql("select e.id, e.legal_entity_id, pa.org_unit_id, pa.location_id from "
                        + EMPLOYMENT_SCOPE_JOIN + " where e.tenant_id = :t and e.id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", employmentId).param("asOf", asOf)
                .query((rs, n) -> new AuthorizationTarget.Employee(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)))
                .optional();
    }

    public boolean can(String permission, UUID employmentId, LocalDate asOf) {
        return target(employmentId, asOf).map(t -> authz.can(permission, t, asOf)).orElse(false);
    }

    /** Throws 404 "Employment" unless the current user may exercise {@code permission} on it. */
    public void require(String permission, UUID employmentId, LocalDate asOf) {
        if (!can(permission, employmentId, asOf)) {
            throw ApiException.notFound("Employment", employmentId);
        }
    }

    /**
     * A person is in scope when any of their employments is; a person without employments (just created, or only
     * pre-hire data) needs a tenant-wide grant. Throws 404 "Person" otherwise.
     */
    public void requirePerson(String permission, UUID personId, LocalDate asOf) {
        UUID tenant = TenantContext.current().tenantId();
        var employments = db.jdbc().sql("select id from hr.employment where tenant_id = :t and person_id = :p")
                .param("t", tenant).param("p", personId).query(UUID.class).list();
        boolean allowed = employments.isEmpty()
                ? authz.hasTenantWidePermission(permission) && personExists(tenant, personId)
                : employments.stream().anyMatch(e -> can(permission, e, asOf));
        if (!allowed) {
            throw ApiException.notFound("Person", personId);
        }
    }

    /** The user's scope of {@code permission} on {@code asOf} as SQL over {@link #EMPLOYMENT_SCOPE_JOIN}. */
    public SqlFragment scope(String permission, LocalDate asOf, String paramPrefix) {
        return authz.scopeFor(permission, asOf).toSql(COLUMNS, paramPrefix);
    }

    private boolean personExists(UUID tenant, UUID personId) {
        return db.jdbc().sql("select exists (select 1 from hr.person where tenant_id = :t and id = :p)")
                .param("t", tenant).param("p", personId).query(Boolean.class).single();
    }
}
