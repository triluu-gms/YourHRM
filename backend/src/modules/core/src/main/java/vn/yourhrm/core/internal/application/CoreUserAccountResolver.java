package vn.yourhrm.core.internal.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.internal.infra.AuditWriter;
import vn.yourhrm.core.internal.infra.UserAccountRepository;
import vn.yourhrm.shared.auth.api.spi.SupportGrants;
import vn.yourhrm.shared.auth.api.spi.SupportGrants.Grant;
import vn.yourhrm.shared.auth.api.spi.UserAccountResolver;
import vn.yourhrm.shared.common.tenancy.SupportSession;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * identity (Keycloak {@code sub}) -> tenant user account (US-CORE-041). Cached briefly; evicted when the
 * user changes. Runs with the tenant-only context bound by the security filter, so RLS confines the
 * lookup to the token's tenant.
 *
 * <p>Also the gate for platform support access (BR-CORE-041, US-CORE-042): an operator token is accepted only
 * under a support grant of this tenant that the tenant approved, that names this operator and whose window is
 * open. Every request, refused or not, is appended to the tenant audit ({@code SUPPORT_ACCESS} /
 * {@code SUPPORT_ACCESS_DENIED}) in its own transaction, so a refusal is logged even though the request stops.
 */
@Component
public class CoreUserAccountResolver implements UserAccountResolver {

    private static final Logger log = LoggerFactory.getLogger(CoreUserAccountResolver.class);

    private final UserAccountRepository users;
    private final AuthorisationCache cache;
    private final ObjectProvider<SupportGrants> supportGrants;
    private final AuditWriter audit;
    private final BusinessClock clock;
    private final TransactionTemplate readTx;
    private final TransactionTemplate auditTx;

    public CoreUserAccountResolver(UserAccountRepository users, AuthorisationCache cache,
            ObjectProvider<SupportGrants> supportGrants, AuditWriter audit, BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.cache = cache;
        this.supportGrants = supportGrants;
        this.audit = audit;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.auditTx = new TransactionTemplate(transactionManager);
        this.auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<ResolvedUser> resolve(UUID tenantId, UUID identityId) {
        return cache.identity(tenantId, identityId, k -> readTx.execute(s ->
                users.findByTenantIdAndIdentityId(tenantId, identityId)
                        .map(u -> new ResolvedUser(u.getId(), u.getStatus().name(), u.getLocale(),
                                u.mayAccess(clock.now())))));
    }

    @Override
    public Optional<SupportSession> resolveSupport(UUID tenantId, UUID operatorId, UUID supportGrantId,
            String method, String path) {
        SupportGrants grants = supportGrants.getIfAvailable();
        // Fail closed: without the SaaS grant registry no support session can be verified.
        Optional<Grant> grant = grants == null ? Optional.empty() : grants.find(tenantId, supportGrantId);
        String refusal = grants == null ? "NO_GRANT_REGISTRY"
                : grant.isEmpty() ? "UNKNOWN_GRANT"
                : !grant.get().tenantId().equals(tenantId) ? "OTHER_TENANT"
                : !grant.get().operatorId().equals(operatorId) ? "OTHER_OPERATOR"
                : !grant.get().usableAt(clock.now()) ? "GRANT_NOT_USABLE:" + grant.get().status()
                : null;
        Map<String, String> details = new LinkedHashMap<>();
        details.put("method", method);
        details.put("path", path);
        if (refusal != null) {
            details.put("reason", refusal);
            log.warn("Support access refused for operator {} in tenant {}: {}", operatorId, tenantId, refusal);
        }
        auditTx.executeWithoutResult(s -> audit.append(new AuditWriter.Event(tenantId, "OPERATOR", null, operatorId,
                supportGrantId, refusal == null ? "SUPPORT_ACCESS" : "SUPPORT_ACCESS_DENIED", "http_request", null,
                null, details, false)));
        if (refusal != null) {
            return Optional.empty();
        }
        Grant g = grant.get();
        return Optional.of(new SupportSession(operatorId, g.id(), g.modules(), g.readWrite(), g.includesSensitive(),
                g.validTo()));
    }
}
