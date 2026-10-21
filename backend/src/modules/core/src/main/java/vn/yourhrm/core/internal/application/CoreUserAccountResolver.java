package vn.yourhrm.core.internal.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.internal.infra.UserAccountRepository;
import vn.yourhrm.shared.auth.api.spi.UserAccountResolver;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * identity (Keycloak {@code sub}) -> tenant user account (US-CORE-041). Cached briefly; evicted when the
 * user changes. Runs with the tenant-only context bound by the security filter, so RLS confines the
 * lookup to the token's tenant.
 */
@Component
public class CoreUserAccountResolver implements UserAccountResolver {

    private final UserAccountRepository users;
    private final AuthorisationCache cache;
    private final BusinessClock clock;
    private final TransactionTemplate readTx;

    public CoreUserAccountResolver(UserAccountRepository users, AuthorisationCache cache, BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.cache = cache;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    @Override
    public Optional<ResolvedUser> resolve(UUID tenantId, UUID identityId) {
        return cache.identity(tenantId, identityId, k -> readTx.execute(s ->
                users.findByTenantIdAndIdentityId(tenantId, identityId)
                        .map(u -> new ResolvedUser(u.getId(), u.getStatus().name(), u.getLocale(),
                                u.mayAccess(clock.now())))));
    }
}
