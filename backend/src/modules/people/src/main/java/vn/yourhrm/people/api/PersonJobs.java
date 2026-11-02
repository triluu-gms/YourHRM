package vn.yourhrm.people.api;

import java.time.LocalDate;

/**
 * Daily person-data monitors (US-PPL-007, US-PPL-011), run once per tenant inside that tenant's {@code TenantContext}
 * by the platform scheduler. Idempotent: a re-run on the same day sends nothing new (notification dedupe keys) and
 * status changes only apply once.
 */
public interface PersonJobs {

    /**
     * Identity documents (passport, visa, residence card ...): marks ACTIVE documents past their expiry date EXPIRED
     * and notifies HR {@code hr.identity_doc_expiry_alert_days} days before the expiry date (tenant setting).
     *
     * @return number of alerts sent
     */
    int identityDocumentExpiryAlerts(LocalDate today);

    /**
     * Work permits of foreign workers: marks ACTIVE permits past their validity EXPIRED and notifies HR at the two
     * configurable offsets ({@code hr.work_permit_expiry_alert_days_first} / {@code _second}, default 60 / 45).
     *
     * @return number of alerts sent
     */
    int workPermitExpiryAlerts(LocalDate today);
}
