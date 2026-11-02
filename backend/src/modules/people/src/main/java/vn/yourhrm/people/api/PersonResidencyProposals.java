package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Intake of Payroll's 183-day tax-residency proposals (IN-32, BR-PAY-065 → BR-PPL-023, US-PPL-014). Payroll runs the
 * 183-day check and proposes; People never computes it. A proposal lands in HR's review queue as an
 * {@code hr.tax_residency} row with {@code source = PAYROLL_PROPOSAL} and {@code approval_status = PENDING_APPROVAL};
 * HR approves it (it becomes the APPROVED status, closing the previous one) or rejects it with a reason.
 *
 * <p>Called by Payroll inside the tenant's {@code TenantContext} (Payroll does not exist yet: declared now so the
 * contract is fixed). Idempotent on {@code proposalRef}.
 */
public interface PersonResidencyProposals {

    /**
     * @param proposalRef payroll 183-day check run / proposal id (idempotency key)
     * @param status      RESIDENT | NON_RESIDENT
     * @param basis       DAYS_183, MONTHS_12, PERMANENT_RESIDENCE, LEASE_183 | NOT_MET, TREATY_TIE_BREAKER
     * @param validTo     exclusive; null = open
     */
    record Proposal(UUID personId, String proposalRef, short taxYear, Short daysInVn, String status, String basis,
            LocalDate validFrom, LocalDate validTo) {
    }

    /** Files the proposal for HR review; returns the id of the pending {@code hr.tax_residency} row. */
    UUID receive(Proposal proposal);
}
