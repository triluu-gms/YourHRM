package vn.yourhrm.shared.common.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Immutable per-request tenant context (ADR-0003 decision 2, architecture-overview §5.2).
 *
 * <p>Bound with {@link ScopedValue} for the duration of a request / job execution. The tenant and the
 * identity come ONLY from the validated access token (or from a persisted job / event record), never
 * from request headers, query parameters or bodies.
 *
 * @param tenantId      tenant of the caller (JWT claim)
 * @param userAccountId {@code core.user_account.id} of the caller; {@code null} only while the identity
 *                      is being resolved to a user account
 * @param identityId    Keycloak user id (JWT {@code sub})
 * @param requestId     correlation id for logs / audit ({@code app.request_id})
 * @param locale        the user's locale (BCP 47), may be {@code null} until resolved
 */
public record TenantContext(UUID tenantId, UUID userAccountId, String identityId, String requestId, String locale) {

    private static final ScopedValue<TenantContext> CURRENT = ScopedValue.newInstance();

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
    }

    /** The bound context; fails fast when code runs without one (ADR-0003). */
    public static TenantContext current() {
        if (!CURRENT.isBound()) {
            throw new NoTenantContextException();
        }
        return CURRENT.get();
    }

    public static Optional<TenantContext> optional() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    public static boolean isBound() {
        return CURRENT.isBound();
    }

    /** Runs {@code op} with {@code ctx} bound; the binding ends when {@code op} returns. */
    public static <T> T callWith(TenantContext ctx, Callable<T> op) throws Exception {
        return ScopedValue.where(CURRENT, ctx).call(op::call);
    }

    public static void runWith(TenantContext ctx, Runnable op) {
        ScopedValue.where(CURRENT, ctx).run(op);
    }

    public TenantContext withUser(UUID userAccountId, String locale) {
        return new TenantContext(tenantId, userAccountId, identityId, requestId, locale);
    }

    /** Cache-key prefix for tenant data (architecture-overview §5.2: keys are prefixed {@code t:{tenantId}:}). */
    public String cachePrefix() {
        return "t:" + tenantId + ":";
    }
}
