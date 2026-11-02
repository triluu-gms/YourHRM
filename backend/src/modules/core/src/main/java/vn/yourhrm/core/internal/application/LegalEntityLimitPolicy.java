package vn.yourhrm.core.internal.application;

import java.util.OptionalLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.common.entitlement.TenantLimits;

/**
 * Resolves the plan's maximum number of legal entities (BR-CORE-001, C-02). The SaaS module supplies
 * {@link TenantLimits}; until it exists the fallback is {@code yhrm.entitlements.max-legal-entities}
 * (unset = no limit).
 */
@Component
class LegalEntityLimitPolicy {

    private final ObjectProvider<TenantLimits> tenantLimits;
    private final Long fallback;

    LegalEntityLimitPolicy(ObjectProvider<TenantLimits> tenantLimits,
            @Value("${yhrm.entitlements.max-legal-entities:#{null}}") Long fallback) {
        this.tenantLimits = tenantLimits;
        this.fallback = fallback;
    }

    OptionalLong maxLegalEntities() {
        TenantLimits limits = tenantLimits.getIfAvailable();
        if (limits != null) {
            return limits.limit(TenantLimits.LEGAL_ENTITIES);
        }
        return fallback == null ? OptionalLong.empty() : OptionalLong.of(fallback);
    }
}
