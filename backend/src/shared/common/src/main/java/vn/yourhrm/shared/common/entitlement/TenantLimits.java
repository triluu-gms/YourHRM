package vn.yourhrm.shared.common.entitlement;

import java.util.OptionalLong;

/**
 * Numeric plan limits of the bound tenant, resolved by SaaS from the subscription / entitlements
 * ({@code saas.entitlement}, {@code saas.plan_version.limits}; BR-SAAS-008, C-02). Implemented by the SaaS
 * module; modules only read. An empty result means "no limit is defined for this tenant".
 */
public interface TenantLimits {

    /** Maximum number of legal entities (DRAFT + ACTIVE) per tenant (BR-CORE-001, BR-SAAS-002). */
    String LEGAL_ENTITIES = "limit.legal_entities";

    /** The limit {@code key} for the tenant of the current {@code TenantContext}. */
    OptionalLong limit(String key);
}
