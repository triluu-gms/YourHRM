package vn.yourhrm.people.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Agreed-compensation events of People (EP-PPL-05; OUT-07). Salary amounts are P2: they travel only in
 * {@code people.SalaryChanged}, whose only consumer is Payroll (US-PPL-042).
 */
public final class SalaryEvents {

    private SalaryEvents() {
    }

    /** A contractual allowance of the version, by Payroll component code. */
    public record Allowance(String componentCode, BigDecimal amount, boolean inSiSalary) {
    }

    /**
     * {@code people.SalaryChanged} (OUT-07): a salary-record version took effect or a version was corrected. Payroll
     * freezes the version ({@code salaryRecordId}) used per run (BR-PAY-072).
     *
     * @param changeKind             HIRE, CHANGE or CORRECTION
     * @param supersededRecordId     the version this one replaces (CORRECTION) or closes (CHANGE), when there is one
     * @param retro                  the effective date falls into a locked pay period (US-PPL-039)
     * @param earliestAffectedPeriod {@code yyyy-MM} of the earliest affected pay period when {@code retro}
     */
    public record SalaryChanged(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, String changeKind, UUID salaryRecordId, UUID supersededRecordId, String payBasis,
            String salaryKind, BigDecimal baseSalaryAmount, BigDecimal agreedNetAmount, List<String> netGuaranteedScope,
            BigDecimal siSalaryAmount, String currencyCode, String payScaleCode, String payScaleStepCode,
            BigDecimal probationPct, List<Allowance> allowances, String actionCode, String reasonCode,
            LocalDate validFrom, LocalDate validTo, UUID decisionId, String decisionNo, UUID contractAppendixId,
            String appendixNo, boolean retro, String earliestAffectedPeriod, boolean initialLoad)
            implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.SalaryChanged";
        }
    }
}
