package vn.yourhrm.core.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The numbering service (BR-CORE-085, US-CORE-086): employee numbers, contract numbers, decision numbers ...
 * A series is configured per tenant (counter per legal entity when its scope is LEGAL_ENTITY) with a pattern such as
 * {@code {LE}-{YYYY}-{SEQ:5}} and a reset rule (NEVER, YEARLY, MONTHLY).
 *
 * <p>Gap-free series must be called inside the business transaction: the counter row stays locked until commit and a
 * rollback gives the number back, so concurrent callers never get duplicates or holes. Non-gap-free series take the
 * number in their own short transaction (no waiting, holes possible on rollback).
 */
public interface Numbering {

    /**
     * @param legalEntityId required for LEGAL_ENTITY series (and for the {@code {LE}} token)
     * @param onDate        the business date (year / month of the reset rule and of {@code {YYYY}} tokens)
     */
    String next(String sequenceCode, UUID legalEntityId, LocalDate onDate);
}
