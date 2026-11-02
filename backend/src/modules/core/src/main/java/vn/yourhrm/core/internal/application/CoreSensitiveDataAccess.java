package vn.yourhrm.core.internal.application;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.internal.infra.AuditWriter;
import vn.yourhrm.shared.auth.api.MaskLevel;
import vn.yourhrm.shared.auth.api.SensitiveDataAccess;
import vn.yourhrm.shared.common.tenancy.SupportSession;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Field-masking policy and sensitive-read audit (BR-CORE-034, US-CORE-040). The level comes from
 * {@code role_permission.field_mask_level} of the grants carrying the permission today (union = least restrictive);
 * no grant fails closed to {@link MaskLevel#PII_AND_PAY}.
 */
@Service
public class CoreSensitiveDataAccess implements SensitiveDataAccess {

    private final CoreAuthorizationService authorization;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final BusinessClock clock;

    public CoreSensitiveDataAccess(CoreAuthorizationService authorization, AuditWriter audit, JdbcClient jdbc,
            BusinessClock clock) {
        this.authorization = authorization;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public MaskLevel maskLevelFor(String permission) {
        return authorization.currentSnapshot().grantsFor(permission, clock.today()).stream()
                .map(g -> MaskLevel.valueOf(g.permissions().get(permission).name()))
                .reduce(MaskLevel::leastRestrictive)
                .orElse(MaskLevel.PII_AND_PAY);
    }

    /** Joins the caller's transaction: {@code set_config(..., true)} lasts until it ends. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MaskLevel applyMaskLevel(String permission) {
        MaskLevel level = maskLevelFor(permission);
        jdbc.sql("select set_config('app.mask_level', :l, true)").param("l", level.name())
                .query((rs, n) -> 1).single();
        return level;
    }

    @Override
    @Transactional
    public void recordSensitiveRead(String permission, String objectType, UUID objectId, UUID legalEntityId) {
        TenantContext ctx = TenantContext.current();
        var support = SupportSession.optional();
        audit.append(new AuditWriter.Event(ctx.tenantId(), support.isPresent() ? "OPERATOR" : "USER",
                ctx.userAccountId(), support.map(SupportSession::operatorId).orElse(null),
                support.map(SupportSession::supportGrantId).orElse(null), "READ_SENSITIVE", objectType, objectId,
                legalEntityId, Map.of("permission", permission, "maskLevel", maskLevelFor(permission).name()), true));
    }
}
