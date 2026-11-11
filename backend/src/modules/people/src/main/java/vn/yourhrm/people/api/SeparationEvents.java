package vn.yourhrm.people.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Events of the separation stream (EP-PPL-08, BR-PPL-070..075; OUT-12/13/14, catalogue gap-analysis §6). Payloads carry
 * dates, codes and flags only: no national id, no tax code, no SI number, no personal e-mail, no amount (D7). Payroll
 * computes the severance / job-loss amounts and service time from the service periods (BR-PPL-074).
 */
public final class SeparationEvents {

    private SeparationEvents() {
    }

    /** A service period supplied for the severance computation (kinds of {@code hr.separation_service_period}). */
    public record ServicePeriod(String periodKind, LocalDate periodFrom, LocalDate periodTo, UUID sourceEmploymentId) {
    }

    /**
     * {@code people.TerminationScheduled} (OUT-12, US-PPL-066): the separation was approved and the termination decision
     * issued. Starts Payroll's {@code FinalSettlementProcess} (ADR-0019), T&A's leaver close and Core's access plan.
     * {@code effectiveDate} is the termination date.
     *
     * @param accessRevocationAt        {@code accessRevocationDate} 23:59 in Asia/Ho_Chi_Minh (US-PPL-061 default)
     * @param formerEmployeeEmailOnFile a personal e-mail is on file for the former-employee OTP login (OD-8); the
     *                                  address itself is read through People's API, never carried in the event
     */
    public record TerminationScheduled(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, UUID separationId, UUID decisionId, String decisionNo, String separationType,
            String initiatedBy, String legalGroundCode, String reasonCode, String statutoryCode,
            LocalDate noticeDate, LocalDate lastWorkingDate, LocalDate terminationDate, Integer requiredNoticeDays,
            String noticeDayBasis, Integer noticeShortfallDays, List<String> shortfallConsequences,
            boolean protectedEmployee, LocalDate accessRevocationDate, Instant accessRevocationAt,
            String severanceType, String severanceEligibility, boolean formerEmployeeEmailOnFile, boolean retro)
            implements PeopleEvent {

        public TerminationScheduled {
            shortfallConsequences = shortfallConsequences == null ? List.of() : List.copyOf(shortfallConsequences);
        }

        @Override
        public String eventType() {
            return "people.TerminationScheduled";
        }
    }

    /**
     * {@code people.EmployeeTerminated} (OUT-13, US-PPL-066): the daily job set the employment TERMINATED on the day
     * after the termination date. Carries the final-settlement trigger: legal ground, statutory SI/UI code, severance
     * eligibility and the service periods. {@code effectiveDate} is the termination date (last day of employment).
     */
    public record EmployeeTerminated(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, UUID separationId, UUID decisionId, String separationType, String legalGroundCode,
            String statutoryCode, LocalDate lastWorkingDate, LocalDate terminationDate, Integer noticeShortfallDays,
            String severanceType, String severanceEligibility, List<ServicePeriod> servicePeriods,
            LocalDate accessRevocationDate, Boolean rehireEligible, boolean retro) implements PeopleEvent {

        public EmployeeTerminated {
            servicePeriods = servicePeriods == null ? List.of() : List.copyOf(servicePeriods);
        }

        @Override
        public String eventType() {
            return "people.EmployeeTerminated";
        }
    }

    /**
     * {@code people.TerminationCancelled} (OUT-14): an approved separation was withdrawn with HR's consent or cancelled
     * by HR (US-PPL-065); the termination decision is cancelled. {@code employmentReinstated}: the employment had
     * already been set TERMINATED and was reverted. {@code effectiveDate} is the cancelled termination date.
     */
    public record TerminationCancelled(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, UUID separationId, UUID decisionId, String previousStatus,
            boolean employmentReinstated, String reason) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.TerminationCancelled";
        }
    }
}
