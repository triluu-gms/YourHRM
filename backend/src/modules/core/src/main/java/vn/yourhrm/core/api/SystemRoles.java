package vn.yourhrm.core.api;

/**
 * System roles of a tenant (US-CORE-033): Tenant Owner, HR Admin, ESS, Line Manager, Former Employee and Auditor,
 * built from vendor templates over the global permission catalogue. Run at provisioning and by the release job after
 * modules registered new permissions, once per tenant inside its {@code TenantContext}. Idempotent.
 */
public interface SystemRoles {

    /**
     * Creates missing system roles and brings every system role's permission matrix in line with its template.
     *
     * @return the number of roles created or changed
     */
    int syncSystemRoles();
}
