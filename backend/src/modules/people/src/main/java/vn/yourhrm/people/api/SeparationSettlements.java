package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * People's intake of Payroll's final-settlement result (IN-19 {@code pay.FinalSettlementStatusChanged}, BR-PPL-074/075,
 * US-PPL-064/066). Implemented by People; Payroll (or the event bus adapter, once it exists) calls it in the tenant's
 * {@code TenantContext}. People stores the reference and Payroll's settlement deadline on the separation for display
 * only and never recomputes severance, job-loss allowance or service time. Idempotent per {@code eventId}.
 */
public interface SeparationSettlements {

    /**
     * @param eventId            id of the Payroll event (dedupe key)
     * @param employmentId       the terminated employment
     * @param payrollResultRef   Payroll's final-settlement / severance result id (soft reference)
     * @param status             Payroll's settlement status, e.g. OPEN, CALCULATED, PAID (stored in the audit only)
     * @param settlementDueDate  Payroll's settlement clock (e.g. 14 / 30 working days after termination [verify-legal])
     */
    record FinalSettlementStatus(UUID eventId, UUID employmentId, UUID payrollResultRef, String status,
            LocalDate settlementDueDate) {
    }

    /** @return true when the status was applied, false when the event was already processed */
    boolean finalSettlementStatusChanged(FinalSettlementStatus status);
}
