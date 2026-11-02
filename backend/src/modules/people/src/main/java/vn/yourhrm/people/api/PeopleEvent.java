package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A People domain event ({@code people.<Aggregate><PastTenseVerb>}, gap-analysis §6). Written to {@code hr.outbox_event}
 * in the transaction of the change (NFR-PPL-002) and published in-process. The envelope fields map to the outbox
 * columns; the record as a whole is the JSON payload.
 *
 * <p>Payload rule (D7): never a P3 value (national id, tax code, SI number, bank account number, health detail).
 * Salary amounts (P2) travel only in {@code people.SalaryChanged}.
 */
public interface PeopleEvent {

    /** e.g. {@code people.EmployeeHired}. */
    String eventType();

    UUID tenantId();

    /** The employer the event is about (bus routing, consumers' scope); null only for person-level events. */
    UUID legalEntityId();

    UUID personId();

    /** Null for person-level events (e.g. {@code PersonalDataChanged}). */
    UUID employmentId();

    /** Business date from which the change applies. */
    LocalDate effectiveDate();

    /** Effective date falls into a locked pay period (BR-PPL-037, US-PPL-039). */
    default boolean retro() {
        return false;
    }

    /** Written by the opening-balance import: consumers skip side effects (ADR-0037 §8). */
    default boolean initialLoad() {
        return false;
    }

    default int schemaVersion() {
        return 1;
    }
}
