package vn.yourhrm.shared.common.onboarding;

import java.util.UUID;

/**
 * SPI of the SaaS go-live onboarding (BR-SAAS-088, BR-CORE-114), implemented by the SaaS module: once a legal entity
 * has gone live and its opening balances are locked, opening-balance imports are refused for it (corrections become
 * ordinary audited adjustments). Without an implementation nothing is locked.
 */
public interface OpeningBalanceLocks {

    /** Locked for the legal entity, or for the whole tenant when {@code legalEntityId} is null. */
    boolean isLocked(UUID tenantId, UUID legalEntityId);
}
