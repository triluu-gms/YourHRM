package vn.yourhrm.core.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import vn.yourhrm.shared.common.legal.LegalParameterPackPublished;

/**
 * Minimum-wage region (vùng I–IV) of a location or legal entity as of a date (BR-CORE-006, interface P-03).
 * Core is not the master: the region comes from the Legal Parameter Service map "admin unit → region", applied to
 * the {@code admin_unit_code} of the location / legal-entity version in effect on that date, with any tenant
 * override the legal pack permits. Evaluated in the current {@code TenantContext}; no user permission check
 * (callers are modules that already authorised their own request).
 */
public interface MinWageRegions {

    enum Region {
        I, II, III, IV
    }

    /**
     * The result of one lookup. {@code region} is null when it cannot be determined: no administrative unit on the
     * address, or the legal pack has no row for it.
     */
    record Resolution(UUID legalEntityId, UUID locationId, LocalDate asOf, String adminUnitCode, Region region,
            Long packVersion, boolean overridden) {
    }

    /** Empty when the location does not exist or has no version in effect on {@code asOf}. */
    Optional<Resolution> forLocation(UUID locationId, LocalDate asOf);

    /** Region of the legal entity's registered address (its default region, BR-CORE-002). */
    Optional<Resolution> forLegalEntity(UUID legalEntityId, LocalDate asOf);

    /**
     * BR-CORE-007: re-evaluates every active location and legal entity of the current tenant against the new pack
     * and publishes a region-change event wherever the region differs from the previous pack. Called by the
     * {@code saas.LegalParameterPackPublished} consumer once per tenant, inside that tenant's context.
     *
     * @return the number of region changes published
     */
    int recomputeAfterPackPublished(LegalParameterPackPublished event);
}
