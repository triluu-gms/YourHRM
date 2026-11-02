package vn.yourhrm.shared.auth.api;

import java.util.UUID;

/**
 * Field-level masking policy and sensitive-read audit (BR-CORE-034, US-CORE-040), implemented by Core. Module
 * APIs and reporting ask it before returning sensitive fields; masked values never leave the server.
 *
 * <p>Salary is shown only when the level allows pay AND the caller holds the module's {@code compensation}
 * permission (registered by People); that second check is the module's, with
 * {@link AuthorizationService#hasPermission}.
 */
public interface SensitiveDataAccess {

    /**
     * The current caller's level for {@code permission}: the least restrictive {@code field_mask_level} among the
     * role grants carrying it today. Fails closed: no grant = {@link MaskLevel#PII_AND_PAY}.
     */
    MaskLevel maskLevelFor(String permission);

    /**
     * Sets {@code app.mask_level} for the current transaction to {@link #maskLevelFor} so the DB masking views apply
     * it; must be called inside the transaction that reads them.
     */
    MaskLevel applyMaskLevel(String permission);

    /**
     * Appends a {@code READ_SENSITIVE} event to the tenant audit (every view of sensitive or pay data is audited,
     * BR-CORE-034). Runs in the caller's transaction.
     */
    void recordSensitiveRead(String permission, String objectType, UUID objectId, UUID legalEntityId);
}
