package vn.yourhrm.core.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI implemented by People (consumer-declared, ADR-0001 decision 3; C-10 assignment lookup): does any employee
 * have an active assignment in an org unit on or after a date? Core asks before closing a unit (US-CORE-012 AC).
 *
 * <p>Until the People module exists, Core falls back to {@link #NONE} (no assignments anywhere), so the check
 * cannot block yet.
 */
public interface OrgUnitAssignments {

    /** True when an active assignment in {@code orgUnitId} exists on {@code from} or later. */
    boolean hasActiveAssignments(UUID orgUnitId, LocalDate from);

    /** STUB until People exists (depends on PPL:assignment-lookup). */
    OrgUnitAssignments NONE = (orgUnitId, from) -> false;
}
