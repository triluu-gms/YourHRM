package vn.yourhrm.core.internal.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.yourhrm.core.api.CoreEvents.RoleAssignmentChanged;
import vn.yourhrm.core.api.CoreEvents.RoleChanged;
import vn.yourhrm.core.api.CoreEvents.UserStatusChanged;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.spi.UserAccountResolver.ResolvedUser;

/**
 * L1 caches of the authorisation service (US-CORE-038, ADR-0006 decision 1): Caffeine with a short TTL
 * (default 60 s). Every key starts with the tenant id (architecture-overview §5.2). Entries are evicted
 * AFTER COMMIT of role / assignment / user changes made in this application instance; other instances
 * converge within the TTL (cross-instance invalidation via events/Valkey is deferred).
 */
@Component
public class AuthorisationCache {

    private static final Logger log = LoggerFactory.getLogger(AuthorisationCache.class);

    record UserKey(UUID tenantId, UUID userId) {
    }

    record ScopeKey(UUID tenantId, UUID userId, String permission, LocalDate asOf) {
    }

    record IdentityKey(UUID tenantId, UUID identityId) {
    }

    private final Cache<UserKey, EffectiveAuthorisation> snapshots;
    private final Cache<ScopeKey, ScopePredicate> scopes;
    private final Cache<IdentityKey, Optional<ResolvedUser>> identities;

    public AuthorisationCache(@Value("${yhrm.authz.cache-ttl:60s}") Duration ttl) {
        this.snapshots = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(20_000).recordStats().build();
        this.scopes = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).recordStats().build();
        this.identities = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(20_000).build();
    }

    EffectiveAuthorisation snapshot(UUID tenantId, UUID userId, Function<UserKey, EffectiveAuthorisation> loader) {
        return snapshots.get(new UserKey(tenantId, userId), loader);
    }

    ScopePredicate scope(UUID tenantId, UUID userId, String permission, LocalDate asOf,
            Function<ScopeKey, ScopePredicate> loader) {
        return scopes.get(new ScopeKey(tenantId, userId, permission, asOf), loader);
    }

    Optional<ResolvedUser> identity(UUID tenantId, UUID identityId, Function<IdentityKey, Optional<ResolvedUser>> loader) {
        return identities.get(new IdentityKey(tenantId, identityId), loader);
    }

    public void evictUser(UUID tenantId, UUID userId) {
        snapshots.invalidate(new UserKey(tenantId, userId));
        scopes.asMap().keySet().removeIf(k -> k.tenantId().equals(tenantId) && k.userId().equals(userId));
        identities.asMap().entrySet().removeIf(e -> e.getKey().tenantId().equals(tenantId)
                && e.getValue().map(u -> u.userAccountId().equals(userId)).orElse(true));
    }

    public void evictTenant(UUID tenantId) {
        snapshots.asMap().keySet().removeIf(k -> k.tenantId().equals(tenantId));
        scopes.asMap().keySet().removeIf(k -> k.tenantId().equals(tenantId));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(RoleAssignmentChanged e) {
        log.debug("Evicting authorisation cache of user {} ({})", e.userAccountId(), e.change());
        evictUser(e.tenantId(), e.userAccountId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(RoleChanged e) {
        log.debug("Evicting authorisation cache of tenant {} (role {} {})", e.tenantId(), e.roleId(), e.change());
        evictTenant(e.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(UserStatusChanged e) {
        evictUser(e.tenantId(), e.userAccountId());
    }

    /** Hit rate of the snapshot cache (diagnostics / tests). */
    public double snapshotHitRate() {
        return snapshots.stats().hitRate();
    }
}
