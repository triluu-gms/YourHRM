package vn.yourhrm.people.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.yourhrm.people.api.SalaryEvents.Allowance;

/**
 * People's read API for other modules (OUT-03, US-PPL-081, NFR-PPL-001): an employee snapshot as of a business date
 * and, for the bi-temporal parts (assignment, salary record), as known at an instant. Evaluated in the caller's
 * {@code TenantContext} with the caller's own permissions (a Payroll run's service user, a T&amp;A job, a Core
 * screen):
 * <ul>
 *   <li>the employment must be in the caller's {@code hr.employee.view} scope (otherwise it is absent: empty /
 *       left out of a batch, never an error that reveals it exists);</li>
 *   <li>the salary summary is filled only where the caller holds {@code hr.compensation.view} on that employment
 *       ({@code salaryVisible}); amounts come from the fail-closed masking view and every read is audited
 *       (BR-PPL-044);</li>
 *   <li>the person summary is masked: P3 identifiers appear only as {@code ••••} + last 4 characters, the birth
 *       date follows the caller's field-masking level; no P3 value is ever returned here (consumers that need one use
 *       the dedicated, audited People endpoints with {@code hr.person.view_sensitive}).</li>
 * </ul>
 * Employment and contract are current-state (Pattern A) data: {@code asKnownAt} does not apply to them.
 */
public interface EmployeeDirectory {

    /** Masked person data (no P3 value). {@code birthDate} is reduced to 1 January unless the caller sees PII. */
    record PersonSummary(UUID personId, String familyName, String middleName, String givenName, String fullName,
            String preferredName, LocalDate birthDate, String genderCode, String nationalityCode,
            String nationalIdType, String nationalIdMasked, String taxCodeMasked, String siNumberMasked) {
    }

    /**
     * @param activeOn the employment covers {@code asOf} (hire date ≤ asOf ≤ end date) and is not cancelled
     * @param endDate  termination date, inclusive
     */
    record EmploymentSummary(UUID employmentId, UUID personId, UUID legalEntityId, String employeeNo, String status,
            boolean activeOn, String hireType, LocalDate hireDate, LocalDate originalHireDate,
            LocalDate seniorityDate, LocalDate probationEndDate, LocalDate endDate, LocalDate lastWorkingDate,
            String workerCategoryCode, String employeeCategoryCode, String workingRegimeCode,
            UUID primaryEmploymentIdForPit) {
    }

    /** An assignment version valid on {@code asOf} ({@code validTo} exclusive). */
    record AssignmentSummary(UUID assignmentId, UUID assignmentKey, String assignmentType, UUID orgUnitId,
            UUID positionId, UUID jobTitleId, UUID gradeId, UUID managerEmploymentId, UUID locationId,
            UUID costCentreId, BigDecimal fte, LocalDate validFrom, LocalDate validTo, Instant recordedAt) {
    }

    /** The contract in force on {@code asOf} ({@code endDate} inclusive). */
    record ContractSummary(UUID contractId, String contractNo, String contractTypeCode, Integer definiteSeq,
            LocalDate startDate, LocalDate endDate, LocalDate signDate, String status) {
    }

    /** The salary-record version valid on {@code asOf} (P2; only with {@code hr.compensation.view}). */
    record SalarySummary(UUID salaryRecordId, String payBasis, String salaryKind, BigDecimal baseSalaryAmount,
            BigDecimal agreedNetAmount, List<String> netGuaranteedScope, BigDecimal siSalaryAmount,
            String currencyCode, String payScaleCode, String payScaleStepCode, BigDecimal probationPct,
            List<Allowance> allowances, LocalDate validFrom, LocalDate validTo, Instant recordedAt) {
    }

    /**
     * @param primaryAssignment the PRIMARY line valid on {@code asOf}
     * @param assignments       every line valid on {@code asOf} (primary, temporary transfer ...)
     * @param salary            null when none or when {@code salaryVisible} is false
     */
    record EmployeeSnapshot(LocalDate asOf, Instant asKnownAt, PersonSummary person, EmploymentSummary employment,
            AssignmentSummary primaryAssignment, List<AssignmentSummary> assignments, ContractSummary contract,
            boolean salaryVisible, SalarySummary salary) {
    }

    /** One employee; empty when unknown or outside the caller's {@code hr.employee.view} scope. */
    Optional<EmployeeSnapshot> snapshot(UUID employmentId, LocalDate asOf, Instant asKnownAt);

    /**
     * The employments of a legal entity that cover {@code asOf} (for a payroll run), in the caller's scope, ordered by
     * employee number; set-based (a fixed number of queries per page, NFR-PPL-001). {@code limit} is capped at
     * {@link #MAX_BATCH}.
     */
    List<EmployeeSnapshot> snapshots(UUID legalEntityId, LocalDate asOf, Instant asKnownAt, int limit, int offset);

    /** Largest page of {@link #snapshots}. */
    int MAX_BATCH = 2000;
}
