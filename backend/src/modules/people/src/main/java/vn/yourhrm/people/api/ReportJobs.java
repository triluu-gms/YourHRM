package vn.yourhrm.people.api;

import java.time.LocalDate;

/**
 * Daily work of the statutory-report stream (BR-PPL-141/142, US-PPL-069/070), run by the platform job scheduler once
 * per tenant inside that tenant's {@code TenantContext}. Idempotent and re-runnable on the same day.
 */
public interface ReportJobs {

    /** What one run did. */
    record Summary(int deadlineAlerts) {
    }

    /**
     * For {@code today}: alerts the holders of {@code hr.statutory_report.execute} of each legal entity when a
     * 6-monthly labour change report or a monthly labour fluctuation notice is due within one of the tenant's alert
     * offsets and has not been submitted yet. Due dates come from legal parameters.
     */
    Summary runDaily(LocalDate today);
}
