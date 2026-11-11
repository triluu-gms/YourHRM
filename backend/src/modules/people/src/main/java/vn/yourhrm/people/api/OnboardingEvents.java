package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Events of the opening-balance employee import (NFR-PPL-007, US-PPL-091, ADR-0037 §8). Every record carries
 * {@code initialLoad = true} so that consumers re-sync their projections without side effects (no invitations,
 * notifications or retro detection).
 */
public final class OnboardingEvents {

    private OnboardingEvents() {
    }

    /**
     * {@code people.EmployeeHired} of an employment loaded by the opening-balance import: same payload as
     * {@link EmploymentEvents.EmployeeHired} plus {@code initialLoad} (always true) (the employment stream's record has no
     * initial-load flag; see the stream report). {@code preview} is true when the hire date is after the go-live date.
     */
    public record InitialEmployeeHired(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, boolean preview, String hireType, String employeeNo, String fullName,
            LocalDate hireDate, LocalDate originalHireDate, LocalDate seniorityDate, String workerCategoryCode,
            String employeeCategoryCode, String workingRegimeCode, UUID previousEmploymentId, UUID orgUnitId,
            UUID positionId, UUID jobTitleId, UUID locationId, UUID managerEmploymentId, UUID decisionId,
            String decisionNo, List<String> missingData, boolean initialLoad) implements PeopleEvent {

        public InitialEmployeeHired {
            missingData = missingData == null ? List.of() : List.copyOf(missingData);
        }

        @Override
        public String eventType() {
            return "people.EmployeeHired";
        }
    }
}
