package vn.yourhrm.core.internal.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CoreEvents.JobCatalogueChanged;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Cost centres (BR-CORE-025, US-CORE-022): each belongs to one legal entity and is valid over
 * {@code [valid_from, valid_to)}; outside that window it cannot be chosen (org units validate it). Org units carry
 * their default cost centre ({@code org_unit_version.cost_centre_id}); per-position defaults are P2 and the
 * percentage split on assignments is owned by People. Scoped by legal entity.
 */
@Service
public class CostCentreService {

    private static final ScopePredicate.Columns SCOPE_COLUMNS = new ScopePredicate.Columns("c.legal_entity_id", null,
            null, null);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;

    public CostCentreService(JdbcClient jdbc, AuthorizationService authz, ApplicationEventPublisher events,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.events = events;
        this.clock = clock;
    }

    public record CostCentreView(UUID id, UUID legalEntityId, String code, String name, String glSegment,
            UUID ownerUserId, LocalDate validFrom, LocalDate validTo, int version) {
    }

    /** Cost centres valid on {@code asOf} (every period when {@code allDates}), within the caller's scope. */
    @Transactional(readOnly = true)
    public List<CostCentreView> list(LocalDate asOf, UUID legalEntityId, boolean allDates) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        SqlFragment scope = authz.scopeFor(CorePermissions.COST_CENTRE_VIEW, date).toSql(SCOPE_COLUMNS, "s");
        return jdbc.sql("""
                        select * from core.cost_centre c
                         where c.tenant_id = :t and (cast(:le as uuid) is null or c.legal_entity_id = :le)
                           and (:all or (c.valid_from <= :d and (c.valid_to is null or c.valid_to > :d)))
                           and\s""" + scope.sql() + " order by c.code")
                .param("t", tenant()).param("le", legalEntityId).param("all", allDates).param("d", date)
                .params(scope.params())
                .query((rs, n) -> map(rs)).list();
    }

    @Transactional(readOnly = true)
    public CostCentreView get(UUID id) {
        CostCentreView c = find(id);
        if (!authz.can(CorePermissions.COST_CENTRE_VIEW, new AuthorizationTarget.LegalEntity(c.legalEntityId()),
                clock.today())) {
            throw ApiException.notFound("Cost centre", id);
        }
        return c;
    }

    @Transactional
    public CostCentreView create(UUID legalEntityId, String code, String name, String glSegment, UUID ownerUserId,
            LocalDate validFrom, LocalDate validTo) {
        UUID tenant = tenant();
        LocalDate from = validFrom == null ? clock.today() : validFrom;
        if (!authz.can(CorePermissions.COST_CENTRE_CREATE, new AuthorizationTarget.LegalEntity(legalEntityId), from)) {
            throw ApiException.notFound("Legal entity", legalEntityId);
        }
        if (!jdbc.sql("select exists (select 1 from core.legal_entity where tenant_id = :t and id = :le and status <> 'DISSOLVED')")
                .param("t", tenant).param("le", legalEntityId).query(Boolean.class).single()) {
            throw ApiException.unprocessable("legal-entity-dissolved", "Legal entity " + legalEntityId
                    + " does not exist or is dissolved");
        }
        if (jdbc.sql("select exists (select 1 from core.cost_centre where tenant_id = :t and legal_entity_id = :le and code = :c)")
                .param("t", tenant).param("le", legalEntityId).param("c", code).query(Boolean.class).single()) {
            throw ApiException.conflict("cost-centre-code-taken",
                    "Cost centre " + code + " already exists in this legal entity");
        }
        checkPeriod(from, validTo);
        checkOwner(tenant, ownerUserId);
        UUID id = Ids.newId();
        jdbc.sql("""
                        insert into core.cost_centre (tenant_id, id, legal_entity_id, code, name, gl_segment, owner_user_id,
                               valid_from, valid_to, created_by, updated_by)
                        values (:t, :id, :le, :c, :n, :gl, :owner, :from, :to, :actor, :actor)""")
                .param("t", tenant).param("id", id).param("le", legalEntityId).param("c", code).param("n", name)
                .param("gl", glSegment).param("owner", ownerUserId).param("from", from).param("to", validTo)
                .param("actor", TenantContext.current().userAccountId())
                .update();
        events.publishEvent(new JobCatalogueChanged(tenant, "COST_CENTRE", id, "CREATED"));
        return find(id);
    }

    /**
     * Updates name, GL segment, owner and end date. Ending a cost centre is refused while an org unit still uses it
     * as default after that date (US-CORE-022: no inactive cost centre may stay selected).
     */
    @Transactional
    public CostCentreView update(UUID id, String name, String glSegment, UUID ownerUserId, LocalDate validTo,
            int expectedVersion) {
        UUID tenant = tenant();
        CostCentreView c = find(id);
        if (!authz.can(CorePermissions.COST_CENTRE_UPDATE, new AuthorizationTarget.LegalEntity(c.legalEntityId()),
                clock.today())) {
            throw ApiException.notFound("Cost centre", id);
        }
        checkPeriod(c.validFrom(), validTo);
        checkOwner(tenant, ownerUserId);
        if (validTo != null) {
            List<String> users = jdbc.sql("""
                            select distinct u.code from core.org_unit_version v
                              join core.org_unit u on u.tenant_id = v.tenant_id and u.id = v.org_unit_id
                             where v.tenant_id = :t and v.cost_centre_id = :id and v.status = 'ACTIVE'
                               and (v.valid_to is null or v.valid_to > :to)
                             order by 1""")
                    .param("t", tenant).param("id", id).param("to", validTo).query(String.class).list();
            if (!users.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "cost-centre-in-use",
                        "Org units still use cost centre " + c.code() + " after " + validTo + ": " + users,
                        Map.of("orgUnits", users));
            }
        }
        int updated = jdbc.sql("""
                        update core.cost_centre
                           set name = :n, gl_segment = :gl, owner_user_id = :owner, valid_to = :to, updated_by = :actor,
                               version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("n", name).param("gl", glSegment).param("owner", ownerUserId).param("to", validTo)
                .param("actor", TenantContext.current().userAccountId())
                .param("t", tenant).param("id", id).param("v", expectedVersion)
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale-version", "Cost centre " + c.code() + " was changed (version "
                    + c.version() + "); reload and retry");
        }
        events.publishEvent(new JobCatalogueChanged(tenant, "COST_CENTRE", id, "UPDATED"));
        return find(id);
    }

    private static void checkPeriod(LocalDate from, LocalDate to) {
        if (to != null && !to.isAfter(from)) {
            throw ApiException.unprocessable("invalid-period", "The end date must be after " + from);
        }
    }

    private void checkOwner(UUID tenant, UUID ownerUserId) {
        if (ownerUserId != null && !jdbc.sql(
                        "select exists (select 1 from core.user_account where tenant_id = :t and id = :u and status = 'ACTIVE')")
                .param("t", tenant).param("u", ownerUserId).query(Boolean.class).single()) {
            throw ApiException.unprocessable("invalid-owner", "Owner " + ownerUserId + " is not an active user");
        }
    }

    private CostCentreView find(UUID id) {
        return jdbc.sql("select * from core.cost_centre c where c.tenant_id = :t and c.id = :id")
                .param("t", tenant()).param("id", id).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Cost centre", id));
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static CostCentreView map(ResultSet rs) throws SQLException {
        return new CostCentreView(rs.getObject("id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("gl_segment"),
                rs.getObject("owner_user_id", UUID.class), rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class), rs.getInt("version"));
    }
}
