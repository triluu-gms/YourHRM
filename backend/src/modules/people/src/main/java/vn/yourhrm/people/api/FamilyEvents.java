package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Events of family members and PIT dependants (EP-PPL-03, OUT-17). No P3 value travels (D7): dependant identifiers
 * (national id, tax code) are read through People's API by the consumer that is entitled to them.
 */
public final class FamilyEvents {

    private FamilyEvents() {
    }

    /** Subtypes of {@link DependantChanged}. */
    public static final String REGISTERED = "REGISTERED";
    public static final String ENDED = "ENDED";
    public static final String TRANSFERRED = "TRANSFERRED";

    /**
     * {@code people.DependantChanged} (OUT-17, US-PPL-029/024): a PIT dependant claim was approved (REGISTERED), its
     * end month was approved (ENDED), or the claim moved to the person's new PIT primary employment (TRANSFERRED).
     * The event is about the claiming employment ({@code employmentId}, {@code legalEntityId}); Payroll applies the
     * dependant deduction there for every month of {@code fromMonth..toMonth} (inclusive; {@code toMonth} null = open).
     *
     * @param effectiveDate          first day of the first month the change applies to
     * @param dependantId            {@code hr.pit_dependant.id} of the claim (for TRANSFERRED: the new claim row)
     * @param reasonCode             claim basis: CHILD_UNDER_AGE, CHILD_STUDENT, CHILD_DISABLED, SPOUSE, PARENT,
     *                               OTHER_SUPPORTED
     * @param previousEmploymentId   TRANSFERRED only: the employment that claimed before
     * @param previousLegalEntityId  TRANSFERRED only: its legal entity
     * @param previousDependantId    TRANSFERRED only: the previous claim row (now ending the month before)
     */
    public record DependantChanged(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, String subtype, UUID dependantId, UUID familyMemberId, String relationshipCode,
            String reasonCode, LocalDate fromMonth, LocalDate toMonth, UUID previousEmploymentId,
            UUID previousLegalEntityId, UUID previousDependantId, boolean initialLoad) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.DependantChanged";
        }
    }
}
