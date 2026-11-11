package vn.yourhrm.people.api;

import java.util.UUID;

/**
 * Consumer-declared SPI (ADR-0001 decision 3) for the onboarding readiness items People does not own (IN-29, IN-30,
 * US-PPL-082): the employee's user account (Core), the pay-group assignment (Payroll, #25) and the work-schedule
 * assignment (T&amp;A, #26). People stores none of them. Evaluated in the current {@code TenantContext}.
 *
 * <p>Until the owners implement it People falls back to {@link #NONE} (every item UNKNOWN). In between, the owners'
 * events reach People through {@link IntegrationInbox} and the state they carry is used for the readiness event they
 * trigger (and kept as the last published state of that item).
 */
public interface OnboardingReadinessSources {

    /** READY, MISSING or UNKNOWN. */
    enum ItemState { READY, MISSING, UNKNOWN }

    ItemState userAccount(UUID employmentId);

    ItemState payGroup(UUID employmentId);

    ItemState workSchedule(UUID employmentId);

    /** STUB until Core / Payroll / T&amp;A implement it: nothing is known. */
    OnboardingReadinessSources NONE = new OnboardingReadinessSources() {
        @Override
        public ItemState userAccount(UUID employmentId) {
            return ItemState.UNKNOWN;
        }

        @Override
        public ItemState payGroup(UUID employmentId) {
            return ItemState.UNKNOWN;
        }

        @Override
        public ItemState workSchedule(UUID employmentId) {
            return ItemState.UNKNOWN;
        }
    };
}
