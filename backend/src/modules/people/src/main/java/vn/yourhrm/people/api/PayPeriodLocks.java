package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-declared SPI (ADR-0001 decision 3) for Payroll's locked pay periods (US-PPL-039, BR-PPL-037, GAP-064):
 * People flags an event {@code retro} when its effective date falls on or before the last day of the latest locked
 * (closed) pay period of the legal entity. Payroll implements it once it exists; every People stream that publishes
 * dated HR events (assignment, salary, contract, termination) uses it. Evaluated in the current {@code TenantContext}.
 *
 * <p>Until Payroll exists People falls back to {@link #NONE}: nothing is locked, so no event is flagged retro. That is
 * the safe default for Phase 1 go-live without Payroll: Payroll detects late changes by comparing {@code recorded_at}
 * with its own watermark anyway (ADR-0024).
 */
public interface PayPeriodLocks {

    /** The last day of the latest locked pay period of {@code legalEntityId}; empty when none is locked. */
    Optional<LocalDate> lockedThrough(UUID legalEntityId);

    /** STUB until Payroll exists: no pay period is locked. */
    PayPeriodLocks NONE = legalEntityId -> Optional.empty();
}
