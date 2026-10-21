package vn.yourhrm.shared.auth.api.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Maps the authenticated identity (JWT {@code sub} = Keycloak user id) of a tenant to the tenant user
 * account (US-CORE-041: identity -> tenant user). Implemented by Core ({@code core.user_account.identity_id}).
 * Called with a tenant context bound (tenant only, no user yet).
 */
public interface UserAccountResolver {

    Optional<ResolvedUser> resolve(UUID tenantId, UUID identityId);

    /**
     * @param userAccountId {@code core.user_account.id}
     * @param status        user status (ACTIVE, FORMER_EMPLOYEE, DEACTIVATED, ...)
     * @param locale        preferred locale
     * @param mayAccess     false when the account is deactivated, locked, invited or expired
     */
    record ResolvedUser(UUID userAccountId, String status, String locale, boolean mayAccess) {
    }
}
