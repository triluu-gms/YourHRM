package vn.yourhrm.people.api;

import java.time.LocalDate;

/**
 * Daily work of the labour-contract stream (BR-PPL-052/054/056), run by the platform job scheduler once per tenant
 * inside that tenant's {@code TenantContext}. Idempotent and re-runnable on the same day.
 */
public interface ContractJobs {

    /** What one run did. */
    record Summary(int contractsExpired, int expiryAlerts, int autoConversionFlags, int probationAlerts,
            int suspensionsStarted, int returnAlerts) {
    }

    /**
     * For {@code today}: starts approved suspensions whose first day has come (employment SUSPENDED), marks ACTIVE
     * contracts past their end date EXPIRED, raises the expiry alerts at the tenant's offsets
     * ({@code people.ContractExpiring} + notification to HR and managers), flags definite contracts worked past the
     * legal number of days without a new contract (auto-conversion), and reminds HR of probations ending without a
     * result and of suspended employees due back to work.
     */
    Summary runDaily(LocalDate today);
}
