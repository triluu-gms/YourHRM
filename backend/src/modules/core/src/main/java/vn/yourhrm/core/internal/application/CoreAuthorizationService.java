package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.internal.domain.FieldMaskLevel;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation.AssignmentGrant;
import vn.yourhrm.core.internal.domain.authz.ScopeResolver;
import vn.yourhrm.core.internal.infra.AuthorisationQueries;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopeType;
import vn.yourhrm.shared.auth.api.spi.ReportingLineProvider;
import vn.yourhrm.shared.common.tenancy.SupportSession;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The single authorisation service (US-CORE-037). Default deny. Cache hits need no database access and
 * no transaction; misses load inside a read-only transaction (tenant context applied, RLS active).
 *
 * <p>The principal is the tenant user of the request, or, in a platform support session (BR-CORE-041), the
 * support grant: its snapshot is one TENANT-wide grant of the covered modules' permissions (VIEW only for a READ
 * grant), masked unless the grant includes sensitive data.
 */
@Service
public class CoreAuthorizationService implements AuthorizationService {

    private final AuthorisationQueries queries;
    private final AuthorisationCache cache;
    private final ScopeResolver resolver;
    private final BusinessClock clock;
    private final TransactionTemplate readTx;

    public CoreAuthorizationService(AuthorisationQueries queries, AuthorisationCache cache,
            ObjectProvider<ReportingLineProvider> reportingLines, BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.queries = queries;
        this.cache = cache;
        // People implements ReportingLineProvider; until then the documented stub (no reports) applies.
        this.resolver = new ScopeResolver(queries, reportingLines.getIfAvailable(() -> ReportingLineProvider.NONE));
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** The current principal's snapshot (cached). */
    public EffectiveAuthorisation currentSnapshot() {
        TenantContext ctx = TenantContext.current();
        UUID principal = principalId(ctx);
        var support = SupportSession.optional();
        if (support.isPresent()) {
            return cache.snapshot(ctx.tenantId(), principal, k -> readTx.execute(s -> supportSnapshot(ctx, support.get())));
        }
        return cache.snapshot(ctx.tenantId(), principal,
                k -> readTx.execute(s -> queries.loadSnapshot(k.tenantId(), k.userId(), clock.today())));
    }

    @Override
    public boolean hasPermission(String permission) {
        return !currentSnapshot().grantsFor(permission, clock.today()).isEmpty();
    }

    @Override
    public boolean hasTenantWidePermission(String permission) {
        return currentSnapshot().grantsFor(permission, clock.today()).stream()
                .anyMatch(g -> g.scopeType() == ScopeType.TENANT);
    }

    @Override
    public boolean can(String permission, AuthorizationTarget target) {
        return can(permission, target, clock.today());
    }

    @Override
    public boolean can(String permission, AuthorizationTarget target, LocalDate asOf) {
        return covers(TenantContext.current().tenantId(), scopeFor(permission, asOf), target, asOf);
    }

    @Override
    public ScopePredicate scopeFor(String permission, LocalDate asOf) {
        TenantContext ctx = TenantContext.current();
        UUID principal = principalId(ctx);
        LocalDate today = clock.today();
        // Cold path: snapshot (if not cached) and subtree expansion share ONE read-only transaction.
        return cache.scope(ctx.tenantId(), principal, permission, asOf, k -> readTx.execute(s -> {
            EffectiveAuthorisation snapshot = currentSnapshot();
            var grants = snapshot.grantsFor(permission, today);
            if (grants.isEmpty()) {
                return ScopePredicate.none();
            }
            return resolver.resolve(ctx.tenantId(), grants, snapshot.selfEmploymentId(), asOf);
        }));
    }

    @Override
    public Set<UUID> usersWhoCan(String permission, AuthorizationTarget target, LocalDate asOf) {
        UUID tenant = TenantContext.current().tenantId();
        LocalDate today = clock.today();
        return readTx.execute(s -> {
            Set<UUID> out = new LinkedHashSet<>();
            for (UUID user : new LinkedHashSet<>(queries.candidatesFor(tenant, permission, today))) {
                if (!queries.mayAccess(tenant, user)) {
                    continue;
                }
                EffectiveAuthorisation snapshot = queries.loadSnapshot(tenant, user, today);
                List<AssignmentGrant> grants = snapshot.grantsFor(permission, today);
                if (!grants.isEmpty() && covers(tenant,
                        resolver.resolve(tenant, grants, snapshot.selfEmploymentId(), asOf), target, asOf)) {
                    out.add(user);
                }
            }
            return out;
        });
    }

    /**
     * Holders of role {@code roleCode} (assigned, valid today, able to sign in) whose grants OF THAT ROLE cover
     * {@code target} on {@code asOf}, sorted by id. Used by the workflow engine (BR-CORE-052 "role in the requester's
     * scope", BR-CORE-056 fallback to the legal entity's HR).
     */
    public List<UUID> usersWithRoleCovering(String roleCode, AuthorizationTarget target, LocalDate asOf) {
        UUID tenant = TenantContext.current().tenantId();
        LocalDate today = clock.today();
        return readTx.execute(s -> queries.holdersOfRole(tenant, roleCode, today).stream()
                .filter(user -> queries.mayAccess(tenant, user))
                .filter(user -> {
                    EffectiveAuthorisation snapshot = queries.loadSnapshot(tenant, user, today);
                    List<AssignmentGrant> grants = snapshot.activeOn(today)
                            .filter(g -> roleCode.equals(g.roleCode())).toList();
                    return !grants.isEmpty() && covers(tenant,
                            resolver.resolve(tenant, grants, snapshot.selfEmploymentId(), asOf), target, asOf);
                })
                .sorted().toList());
    }

    /** Does {@code scope} cover {@code target} (organisational attributes resolved as of {@code asOf})? */
    private boolean covers(UUID tenant, ScopePredicate scope, AuthorizationTarget target, LocalDate asOf) {
        if (scope.isEmpty()) {
            return false;
        }
        if (scope.tenantWide()) {
            return true;
        }
        return switch (target) {
            case AuthorizationTarget.Tenant t -> false;
            case AuthorizationTarget.LegalEntity le -> scope.legalEntityIds().contains(le.id());
            case AuthorizationTarget.Location loc -> scope.locationIds().contains(loc.id())
                    || (!scope.legalEntityIds().isEmpty() && readTx.execute(s ->
                            queries.locationLegalEntity(tenant, loc.id())
                                    .map(scope.legalEntityIds()::contains).orElse(false)));
            case AuthorizationTarget.OrgUnit ou -> scope.orgUnitIds().contains(ou.id())
                    || ((!scope.legalEntityIds().isEmpty() || !scope.locationIds().isEmpty()) && readTx.execute(s ->
                            queries.orgUnitPlacement(tenant, ou.id(), asOf)
                                    .map(p -> scope.covers(p.legalEntityId(), null, p.locationId(), null))
                                    .orElse(false)));
            case AuthorizationTarget.Employee e ->
                    scope.covers(e.legalEntityId(), e.orgUnitId(), e.locationId(), e.employmentId());
        };
    }

    /** One TENANT-wide grant built from the support grant (valid today; the filter re-checks the grant per request). */
    private EffectiveAuthorisation supportSnapshot(TenantContext ctx, SupportSession session) {
        FieldMaskLevel mask = session.includesSensitive() ? FieldMaskLevel.NONE : FieldMaskLevel.PII_AND_PAY;
        Map<String, FieldMaskLevel> permissions = new LinkedHashMap<>();
        queries.permissionsOfModules(session.modules()).forEach((code, action) -> {
            boolean read = "VIEW".equals(action) || ("VIEW_SENSITIVE".equals(action) && session.includesSensitive());
            if (read || session.readWrite()) {
                permissions.put(code, mask);
            }
        });
        LocalDate today = clock.today();
        AssignmentGrant grant = new AssignmentGrant(null, null, "PLATFORM_SUPPORT", "Hỗ trợ nền tảng",
                "Platform support", ScopeType.TENANT, Set.of(), true, today, today.plusDays(1), permissions);
        return new EffectiveAuthorisation(ctx.tenantId(), null, null, List.of(grant));
    }

    /** The tenant user, or the support grant of a platform support session; anything else is refused. */
    private static UUID principalId(TenantContext ctx) {
        if (ctx.userAccountId() != null) {
            return ctx.userAccountId();
        }
        return SupportSession.optional().map(SupportSession::supportGrantId)
                .orElseThrow(() -> new IllegalStateException("No user account bound in the tenant context"));
    }
}
