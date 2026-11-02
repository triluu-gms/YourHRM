package vn.yourhrm.people.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Assignment events of People (EP-PPL-04; catalogue gap-analysis §6, OUT-04 / OUT-06). Written to the outbox in the
 * People transaction of the change. No P3 values, no salary amounts.
 */
public final class AssignmentEvents {

    private AssignmentEvents() {
    }

    /** One cost-centre share of an assignment (BR-PPL-031, US-PPL-034). */
    public record CostShare(UUID costCentreId, BigDecimal pct) {
    }

    /**
     * {@code people.AssignmentChanged} (OUT-04): an assignment version took effect, or a past version was corrected.
     * Published when the change is approved if its effective date has come, otherwise by the daily job on that date
     * (US-PPL-038). Carries the reporting line ({@code managerEmploymentId}) for Core's data scopes.
     *
     * @param changeKind            HIRE (first line of an employment), CHANGE (a new version from {@code effectiveDate}),
     *                              CORRECTION (a version replaced with the same validity, US-PPL-037)
     * @param retro                 the effective date falls into a locked pay period of the legal entity (US-PPL-039)
     * @param earliestAffectedPeriod {@code yyyy-MM} of the earliest affected pay period when {@code retro}
     * @param contractAppendixId    the appendix that carries changed contract terms, when one was given
     */
    public record AssignmentChanged(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, String changeKind, UUID assignmentId, UUID assignmentKey, String assignmentType,
            String actionCode, String reasonCode, UUID orgUnitId, UUID positionId, UUID jobTitleId, UUID gradeId,
            UUID managerEmploymentId, UUID previousManagerEmploymentId, UUID locationId, UUID costCentreId,
            List<CostShare> costSplit, BigDecimal fte, LocalDate validFrom, LocalDate validTo, UUID decisionId,
            String decisionNo, UUID contractAppendixId, boolean retro, String earliestAffectedPeriod,
            boolean initialLoad) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.AssignmentChanged";
        }
    }

    /**
     * {@code people.AssignmentChangeCancelled} (US-PPL-038; not yet in the catalogue): an approved future-dated change
     * was cancelled before its effective date; the previous version runs on.
     */
    public record AssignmentChangeCancelled(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, UUID assignmentId, UUID assignmentKey, String assignmentType, String reasonCode,
            UUID decisionId, String cancellationReason) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.AssignmentChangeCancelled";
        }
    }

    /**
     * Wage protection of an Art. 29 temporary transfer (BR-PPL-035, US-PPL-040) as Payroll must apply it; all values
     * come from legal parameters in force on the start date ([verify-legal]).
     *
     * @param previousWageWorkingDays the previous wage is kept for this many working days
     * @param newWageMinPctOfPrevious afterwards the new job's wage is at least this % of the previous wage
     * @param notBelowRegionalMinimum and never below the regional minimum wage
     */
    public record WageProtection(int previousWageWorkingDays, BigDecimal newWageMinPctOfPrevious,
            boolean notBelowRegionalMinimum) {
    }

    /**
     * {@code people.TemporaryTransferChanged} (OUT-06; BRD names TemporaryTransferStarted/Ended): status STARTED on
     * the first day, ENDED on the day after the last day, CANCELLED when an approved transfer is cancelled before it
     * starts.
     *
     * @param workingDaysInYear  cumulative working days of temporary transfers in the start date's calendar year,
     *                           this one included
     * @param consentDocumentId  the employee's written consent (Core document), required over the yearly limit
     */
    public record TemporaryTransferChanged(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, String status, UUID assignmentId, UUID assignmentKey, LocalDate startDate,
            LocalDate endDate, UUID orgUnitId, UUID positionId, UUID jobTitleId, int workingDaysInYear,
            UUID consentDocumentId, WageProtection wageProtection, boolean retro, String earliestAffectedPeriod)
            implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.TemporaryTransferChanged";
        }
    }
}
