package vn.yourhrm.core.api;

/**
 * Creates the company group profile of a newly provisioned tenant (C-01 {@code saas.TenantProvisioned},
 * BR-CORE-001, US-CORE-001). Called by the {@code TenantProvisioned} consumer inside the new tenant's
 * {@code TenantContext}. Idempotent: a second call (event redelivery) leaves the existing profile untouched.
 */
public interface CompanyGroupProvisioning {

    /**
     * Ensures the profile exists, defaulting name, locale and time zone from the tenant's {@code saas.tenant} row.
     * Also seeds the default org unit types (US-CORE-010), employee categories (US-CORE-023) and the system roles
     * (US-CORE-033).
     *
     * @return {@code true} when the profile was created by this call
     */
    boolean provision();
}
