package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation;
import vn.yourhrm.core.internal.domain.authz.ScopeResolver;
import vn.yourhrm.core.internal.infra.AuthorisationQueries;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopeType;
import vn.yourhrm.shared.auth.api.spi.ReportingLineProvider;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The single authorisation service (US-CORE-037). Default deny. Cache hits need no database access and
 * no transaction; misses load inside a read-only transaction (tenant context applied, RLS active).
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

    /** The current user's snapshot (cached). */
    public EffectiveAuthorisation currentSnapshot() {
        TenantContext ctx = requireUser();
        return cache.snapshot(ctx.tenantId(), ctx.userAccountId(),
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
        ScopePredicate scope = scopeFor(permission, asOf);
        if (scope.isEmpty()) {
            return false;
        }
        if (scope.tenantWide()) {
            return true;
        }
        TenantContext ctx = TenantContext.current();
        return switch (target) {
            case AuthorizationTarget.Tenant t -> false;
            case AuthorizationTarget.LegalEntity le -> scope.legalEntityIds().contains(le.id());
            case AuthorizationTarget.Location loc -> scope.locationIds().contains(loc.id())
                    || (!scope.legalEntityIds().isEmpty() && readTx.execute(s ->
                            queries.locationLegalEntity(ctx.tenantId(), loc.id())
                                    .map(scope.legalEntityIds()::contains).orElse(false)));
            case AuthorizationTarget.OrgUnit ou -> scope.orgUnitIds().contains(ou.id())
                    || ((!scope.legalEntityIds().isEmpty() || !scope.locationIds().isEmpty()) && readTx.execute(s ->
                            queries.orgUnitPlacement(ctx.tenantId(), ou.id(), asOf)
                                    .map(p -> scope.covers(p.legalEntityId(), null, p.locationId(), null))
                                    .orElse(false)));
            case AuthorizationTarget.Employee e ->
                    scope.covers(e.legalEntityId(), e.orgUnitId(), e.locationId(), e.employmentId());
        };
    }

    @Override
    public ScopePredicate scopeFor(String permission, LocalDate asOf) {
        TenantContext ctx = requireUser();
        LocalDate today = clock.today();
        // Cold path: snapshot (if not cached) and subtree expansion share ONE read-only transaction.
        return cache.scope(ctx.tenantId(), ctx.userAccountId(), permission, asOf, k -> readTx.execute(s -> {
            EffectiveAuthorisation snapshot = currentSnapshot();
            var grants = snapshot.grantsFor(permission, today);
            if (grants.isEmpty()) {
                return ScopePredicate.none();
            }
            return resolver.resolve(ctx.tenantId(), grants, snapshot.selfEmploymentId(), asOf);
        }));
    }

    private static TenantContext requireUser() {
        TenantContext ctx = TenantContext.current();
        if (ctx.userAccountId() == null) {
            throw new IllegalStateException("No user account bound in the tenant context");
        }
        return ctx;
    }

    UUID currentUserId() {
        return requireUser().userAccountId();
    }
}
