package vn.yourhrm.people.internal.application.person;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.application.person.WorkPermitService.Coverage;

/**
 * Internal guard for the assignment and contract streams (BR-PPL-020, US-PPL-011 / US-PPL-050): a foreign worker's
 * assignment end or definite-contract end may not lie beyond the work-permit validity unless a renewal is recorded.
 * Vietnamese nationals pass. Joins the caller's People transaction; no permission check (the calling flow did it).
 */
@Component
public class WorkPermitGuard {

    private final WorkPermitService permits;

    public WorkPermitGuard(WorkPermitService permits) {
        this.permits = permits;
    }

    /**
     * Throws 422 {@code work-permit-coverage-exceeded} when {@code until} (INCLUSIVE last day; null = open-ended) is
     * not covered by ACTIVE permits and no renewal (APPLIED / ACTIVE permit with {@code renewal_of_id}) is recorded.
     */
    public void requireCoverage(UUID employmentId, LocalDate until) {
        permits.requireCoverage(employmentId, until);
    }

    /** The coverage itself, e.g. to warn in a form before submitting. */
    public Coverage coverage(UUID employmentId) {
        return permits.coverage(employmentId);
    }
}
