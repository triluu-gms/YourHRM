package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.yourhrm.shared.common.legal.LegalParameterPackPublished;

/**
 * Daily and event-driven work of the employment stream (EP-PPL-02), run by the platform job scheduler / event bus once
 * per tenant inside that tenant's {@code TenantContext}. Idempotent and re-runnable on the same day.
 */
public interface EmploymentJobs {

    /**
     * What one daily run did.
     *
     * @param activated            approved pre-hires whose hire date has come, now ACTIVE ({@code EmployeeHired})
     * @param blockedByLicence     approved pre-hires not activated because the plan limit of active employees is
     *                             reached (IN-23); they stay PRE_HIRE and are retried every day
     * @param endedPrimaries       PIT primary designations closed because their employment ended
     * @param insuranceRederived   persons whose insurance participation was re-derived after an employment ended
     * @param headcountSnapshots   {@code HeadcountSnapshotTaken} events published (one per legal entity)
     */
    record DailySummary(int activated, List<UUID> blockedByLicence, int endedPrimaries, int insuranceRederived,
            int headcountSnapshots) {
    }

    /**
     * For {@code today}: activates approved PRE_HIRE employments whose hire date has come (final
     * {@code people.EmployeeHired}), closes PIT primary designations of ended employments (re-designation, US-PPL-022),
     * re-derives insurance participation of persons whose employment ended (US-PPL-023) and takes the headcount
     * snapshot per legal entity (US-PPL-026).
     */
    DailySummary runDaily(LocalDate today);

    /** Headcount snapshot of {@code date} per legal entity (idempotent per legal entity and date). */
    int takeHeadcountSnapshot(LocalDate date);

    /**
     * {@code saas.LegalParameterPackPublished}: when {@code insurance.multi_contract_rule} changed, re-derives the
     * insurance participation of every person with a live employment from the earliest changed date (today when the
     * pack gives none). Returns the number of persons whose participation changed.
     */
    int onLegalPackPublished(LegalParameterPackPublished event);
}
