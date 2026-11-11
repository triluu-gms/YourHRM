package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-declared SPI (ADR-0001 decision 3) for Payroll's locked pay periods (US-PPL-039, BR-PPL-037, GAP-064):
 * People flags an event {@code retro} when its effective date falls on or before the last day of the latest locked
 * (closed) pay period of the legal entity. Every People stream that publishes dated HR events (assignment, salary,
 * contract, termination) uses it. Evaluated in the current {@code TenantContext}.
 *
 * <p>People's default implementation reads its projection {@code hr.ext_pay_period_lock}, fed by Payroll's period
 * lock / unlock events through {@link IntegrationInbox#payPeriodLockChanged}; a Payroll implementation registered as a
 * bean takes precedence. With no lock received nothing is locked and no event is flagged retro (Payroll detects late
 * changes by comparing {@code recorded_at} with its own watermark anyway, ADR-0024).
 */
public interface PayPeriodLocks {

    /** The last day of the latest locked pay period of {@code legalEntityId}; empty when none is locked. */
    Optional<LocalDate> lockedThrough(UUID legalEntityId);
}
