package vn.yourhrm.core.api;

/**
 * Scheduled user-lifecycle work of Core (BR-CORE-030, NFR-CORE-015), run by the platform job scheduler once per
 * tenant inside that tenant's {@code TenantContext}. Idempotent and re-runnable.
 */
public interface UserLifecycle {

    /**
     * Deactivates every user whose {@code expires_at} has passed (non-employee users such as auditors and
     * consultants, US-CORE-030 AC) and publishes {@code core.UserStatusChanged} for each. Expired users are already
     * refused at sign-in; this makes the status match.
     *
     * @return the number of users deactivated
     */
    int deactivateExpiredUsers();
}
