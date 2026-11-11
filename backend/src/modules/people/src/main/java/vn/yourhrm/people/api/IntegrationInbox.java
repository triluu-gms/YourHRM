package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * People's inbox for events of other modules (US-PPL-082, ADR-0028 consumer template). The event-bus adapter of the
 * producing module (Core, Payroll, T&amp;A; Phase-1 DB bus on {@code saas.job}) calls it inside the tenant's
 * {@code TenantContext}. Every call is idempotent on (consumer, {@code eventId}) in {@code hr.inbox_message}: a
 * redelivered event returns {@link Outcome#DUPLICATE}; a failing one is kept as FAILED with its error (dead letter,
 * listed by {@code GET /api/v1/people/integration/inbox?status=FAILED}) and is retried on redelivery. A FAILED event
 * never blocks the other events.
 */
public interface IntegrationInbox {

    /** PROCESSED (applied), SKIPPED (nothing to do), DUPLICATE (already handled), FAILED (dead letter). */
    enum Outcome { PROCESSED, SKIPPED, DUPLICATE, FAILED }

    /**
     * IN-29 {@code core.UserStatusChanged} with the user's employment link, when the adapter knows it (Core's
     * in-process event has none; People then records it as SKIPPED).
     */
    Outcome userStatusChanged(UUID eventId, UUID userAccountId, UUID employmentId, String status);

    /** IN-30 {@code pay.PayGroupAssigned}: readiness item PAY_GROUP ({@code assigned=false} when removed). */
    Outcome payGroupAssigned(UUID eventId, UUID employmentId, String payGroupCode, boolean assigned);

    /** IN-30 T&amp;A schedule-assignment status: readiness item WORK_SCHEDULE. */
    Outcome workScheduleAssigned(UUID eventId, UUID employmentId, boolean assigned);

    /** IN-19 {@code pay.FinalSettlementStatusChanged}: routed to {@link SeparationSettlements}. */
    Outcome finalSettlementStatusChanged(UUID eventId, UUID employmentId, UUID payrollResultRef, String status,
            LocalDate settlementDueDate);

    /** IN-32 183-day residency proposal: routed to {@link PersonResidencyProposals} (HR review queue). */
    Outcome residencyProposed(UUID eventId, PersonResidencyProposals.Proposal proposal);

    /**
     * IN-14 {@code att.AbsenceChanged}: upserts (or, for a cancelled / rejected / withdrawn absence, cancels) the row of
     * {@code hr.ext_att_absence} keyed by T&amp;A's absence id. People's default {@link SeparationAbsences} reads that
     * projection for the protected-employee check (BR-PPL-073). {@code absenceTypeCode} is P3: never logged or
     * published by People.
     */
    Outcome absenceChanged(UUID eventId, AbsenceChange change);

    /**
     * Payroll's pay period of {@code legalEntityId} was locked ({@code locked=true}) or reopened: upserts
     * {@code hr.ext_pay_period_lock}, which People's default {@link PayPeriodLocks} reads for the retro flag
     * (BR-PPL-037). A lock keeps the latest locked period end; an unlock moves the lock back before
     * {@code periodStart}.
     */
    Outcome payPeriodLockChanged(UUID eventId, PayPeriodLockChange change);

    /** One {@code att.AbsenceChanged}; {@code status} CANCELLED / REJECTED / WITHDRAWN cancels the absence. */
    record AbsenceChange(UUID absenceId, UUID employmentId, String absenceTypeCode, LocalDate from, LocalDate to,
            boolean protective, String status) {
    }

    /** One Payroll period lock / unlock of a legal entity (period dates inclusive). */
    record PayPeriodLockChange(UUID legalEntityId, String periodCode, LocalDate periodStart, LocalDate periodEnd,
            boolean locked) {
    }
}
