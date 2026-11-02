package vn.yourhrm.shared.common.tenancy;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * A platform support operator acting in a tenant under a tenant-approved, time-boxed support grant
 * (BR-CORE-041, BR-SAAS-034/035, US-CORE-042). Bound next to the {@link TenantContext} (whose user account is
 * then {@code null}) for the duration of one request. The transaction manager writes {@code app.operator_id} and
 * {@code app.support_grant_id}, so the row audit attributes every change to the operator and the grant.
 *
 * @param operatorId        platform operator (saas, soft reference)
 * @param supportGrantId    {@code saas.support_grant.id}
 * @param modules           module codes the grant covers (e.g. {@code core}, {@code ppl})
 * @param readWrite         READ_WRITE grant; otherwise read only
 * @param includesSensitive the grant unmasks sensitive data (P3, pay)
 * @param validTo           end of the grant window
 */
public record SupportSession(UUID operatorId, UUID supportGrantId, Set<String> modules, boolean readWrite,
        boolean includesSensitive, Instant validTo) {

    private static final ScopedValue<SupportSession> CURRENT = ScopedValue.newInstance();

    public SupportSession {
        Objects.requireNonNull(operatorId, "operatorId");
        Objects.requireNonNull(supportGrantId, "supportGrantId");
        modules = Set.copyOf(modules);
    }

    public static Optional<SupportSession> optional() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** Runs {@code op} with {@code session} bound; the binding ends when {@code op} returns. */
    public static <T> T callWith(SupportSession session, Callable<T> op) throws Exception {
        return ScopedValue.where(CURRENT, session).call(op::call);
    }
}
