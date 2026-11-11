package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Employment-lifecycle events of People (EP-PPL-02; OUT-01, OUT-02, OUT-32, OUT-36, OUT-37). No P3 value in any
 * payload (D7); names are P1 and travel only where a consumer provisions from them (Core user provisioning).
 */
public final class EmploymentEvents {

    private EmploymentEvents() {
    }

    /**
     * {@code people.EmployeeHired} (OUT-01, US-PPL-019): {@code preview = true} when the hire was approved before its
     * hire date (consumers may prepare, e.g. Core creates the user account disabled); the final event
     * ({@code preview = false}) is published when the employment becomes ACTIVE on the hire date. Core provisions the
     * user account from it.
     *
     * @param hireType    NEW, REHIRE or CONCURRENT
     * @param missingData mandatory data still missing (the employment is "incomplete", US-PPL-018); codes such as
     *                    NATIONAL_ID, TAX_CODE, SI_NUMBER, BIRTH_DATE, SIGNED_CONTRACT, BANK_ACCOUNT
     */
    public record EmployeeHired(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, boolean preview, String hireType, String employeeNo, String fullName,
            LocalDate hireDate, LocalDate originalHireDate, LocalDate seniorityDate, String workerCategoryCode,
            String employeeCategoryCode, String workingRegimeCode, UUID previousEmploymentId, UUID orgUnitId,
            UUID positionId, UUID jobTitleId, UUID locationId, UUID managerEmploymentId, UUID decisionId,
            String decisionNo, List<String> missingData) implements PeopleEvent {

        public EmployeeHired {
            missingData = missingData == null ? List.of() : List.copyOf(missingData);
        }

        @Override
        public String eventType() {
            return "people.EmployeeHired";
        }
    }

    /**
     * {@code people.HireCancelled} (OUT-02, US-PPL-020): a pre-hire was cancelled before its start (no-show, offer
     * withdrawn) or its approval was rejected; T&A and Payroll drop everything they prepared for the employment.
     *
     * @param cancelKind NO_SHOW (HR cancellation) or APPROVAL_REJECTED
     */
    public record HireCancelled(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, String employeeNo, LocalDate hireDate, String cancelKind, String reasonCode,
            String reason, boolean previewPublished) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.HireCancelled";
        }
    }

    /**
     * {@code people.PrimaryEmploymentChanged} (OUT-36, US-PPL-022): the PIT family-deduction primary employment of a
     * person from {@code validFrom} ({@code validTo} exclusive, null = open). Purpose is always
     * {@code PIT_FAMILY_DEDUCTION} (DDL CHECK). {@code employmentId} is the new primary; {@code previousEmploymentId}
     * the one it replaces (null when the person had a single employment before).
     *
     * @param reasonCode EMPLOYEE_REGISTRATION (approved change), CONCURRENT_HIRE (default designation when a second
     *                   employment starts), PRIMARY_ENDED (the primary ended, the only remaining employment takes over)
     *                   or HIRE_CANCELLED (designation withdrawn)
     */
    public record PrimaryEmploymentChanged(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, String purpose, UUID previousEmploymentId, LocalDate validFrom,
            LocalDate validTo, String reasonCode, UUID primaryEmploymentId) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.PrimaryEmploymentChanged";
        }
    }

    /**
     * {@code people.InsuranceParticipationChanged} (OUT-37, US-PPL-023): the participation of one employment in one
     * compulsory insurance type from {@code validFrom} ({@code validTo} exclusive). {@code status} CANCELLED means the
     * row was withdrawn (re-derivation replaced it). Payroll computes contributions from it (BR-PAY-081).
     *
     * @param insuranceType SI, HI, UI or OA_OD
     * @param basisRule     FIRST_CONTRACT, HIGHEST_SALARY or EACH_EMPLOYER (null only when exempt)
     */
    public record InsuranceParticipationChanged(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, UUID participationId, String insuranceType, boolean participating,
            String basisRule, String exemptionReasonCode, boolean manualOverride, UUID legalPackId,
            Long legalPackVersion, LocalDate validFrom, LocalDate validTo, String status, String trigger)
            implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.InsuranceParticipationChanged";
        }
    }

    /**
     * {@code people.HeadcountSnapshotTaken} (OUT-32, US-PPL-026, D-10, R2-12): employments ACTIVE on
     * {@code snapshotDate} in one legal entity (a person employed by two legal entities counts in each; SUSPENDED is
     * not counted). {@code monthEnd} marks the billable snapshot. Aggregate = legal entity.
     */
    public record HeadcountSnapshotTaken(UUID tenantId, UUID legalEntityId, LocalDate snapshotDate,
            boolean monthEnd, int activeCount, int suspendedCount, int preHireCount) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.HeadcountSnapshotTaken";
        }

        @Override
        public UUID personId() {
            return null;
        }

        @Override
        public UUID employmentId() {
            return null;
        }

        @Override
        public LocalDate effectiveDate() {
            return snapshotDate;
        }
    }
}
