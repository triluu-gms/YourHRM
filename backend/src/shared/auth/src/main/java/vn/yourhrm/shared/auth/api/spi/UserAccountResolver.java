package vn.yourhrm.shared.auth.api.spi;

import java.util.Optional;
import java.util.UUID;
import vn.yourhrm.shared.common.tenancy.SupportSession;

/**
 * Maps the authenticated identity (JWT {@code sub} = Keycloak user id) of a tenant to the tenant user
 * account (US-CORE-041: identity -> tenant user). Implemented by Core ({@code core.user_account.identity_id}).
 * Called with a tenant context bound (tenant only, no user yet).
 */
public interface UserAccountResolver {

    Optional<ResolvedUser> resolve(UUID tenantId, UUID identityId);

    /**
     * Platform support access (BR-CORE-041): validates the support grant of an operator token and records the
     * request ({@code method} {@code path}) in the tenant audit, refused attempts included. Empty = refuse.
     */
    default Optional<SupportSession> resolveSupport(UUID tenantId, UUID operatorId, UUID supportGrantId,
            String method, String path) {
        return Optional.empty();
    }

    /**
     * @param userAccountId {@code core.user_account.id}
     * @param status        user status (ACTIVE, FORMER_EMPLOYEE, DEACTIVATED, ...)
     * @param locale        preferred locale
     * @param mayAccess     false when the account is deactivated, locked, invited or expired
     */
    record ResolvedUser(UUID userAccountId, String status, String locale, boolean mayAccess) {
    }
}
