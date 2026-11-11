package vn.yourhrm.people.api;

import java.time.LocalDate;

/**
 * Daily work of the separation stream (BR-PPL-075, US-PPL-066), run by the platform job scheduler once per tenant
 * inside that tenant's {@code TenantContext}. Idempotent and re-runnable on the same day.
 */
public interface SeparationJobs {

    /** What one run did. */
    record Summary(int employmentsTerminated, int settlementAlerts) {
    }

    /**
     * For {@code today}: completes every scheduled separation whose termination date is before {@code today}
     * (employment TERMINATED with end date and last working date, {@code people.EmployeeTerminated} with the
     * final-settlement trigger) and alerts HR about final settlements due soon (Payroll's clock, tenant offsets).
     */
    Summary runDaily(LocalDate today);
}
