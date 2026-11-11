package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Events of the integration stream (EP-PPL-11). No P3 value, no amount (D7).
 */
public final class IntegrationEvents {

    private IntegrationEvents() {
    }

    /** One onboarding readiness item; {@code state} READY, MISSING or UNKNOWN (the owning module cannot tell yet). */
    public record ReadinessItem(String code, String state) {
    }

    /**
     * {@code people.OnboardingReadinessChanged} (catalogue v1.1, ADR-0019 {@code HireProcess}, US-PPL-082): the
     * readiness of a pre-hire / newly active employment changed. Items: USER_ACCOUNT (Core), PAY_GROUP (Payroll),
     * WORK_SCHEDULE (T&amp;A), SIGNED_CONTRACT, BANK_ACCOUNT, TAX_CODE (People). {@code status} READY when no item is
     * MISSING (UNKNOWN items of modules that are not live do not block), else BLOCKED. {@code effectiveDate} is the
     * hire date.
     *
     * @param trigger what caused the evaluation, e.g. EMPLOYEE_HIRED, CONTRACT_SIGNED, BANK_ACCOUNT_CHANGED,
     *                PAY_GROUP_ASSIGNED, WORK_SCHEDULE_ASSIGNED, USER_STATUS_CHANGED
     */
    public record OnboardingReadinessChanged(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, String status, List<ReadinessItem> items, String trigger) implements PeopleEvent {

        public OnboardingReadinessChanged {
            items = items == null ? List.of() : List.copyOf(items);
        }

        @Override
        public String eventType() {
            return "people.OnboardingReadinessChanged";
        }
    }
}
