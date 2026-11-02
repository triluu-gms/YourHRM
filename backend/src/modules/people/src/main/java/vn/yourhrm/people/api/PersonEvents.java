package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Person-level People events (EP-PPL-01, gap-analysis §6). Payloads are minimised (D7): changed field NAMES plus the
 * non-P3 values consumers need (nationality, tax-residency status / basis / validity, VN entry and exit dates). A P3
 * value (national id, tax code, SI number, HI card, ethnicity, religion, health detail) is never part of a payload:
 * consumers fetch it through People's read API with their own permission.
 */
public final class PersonEvents {

    private PersonEvents() {
    }

    /** Tax-residency values published with {@link PersonalDataChanged} (BR-PPL-023, OUT-15). */
    public record TaxResidency(UUID taxResidencyId, String status, String basis, LocalDate validFrom, LocalDate validTo,
            LocalDate arrivalDate, LocalDate departureDate, String source) {
    }

    /** An entry into / exit from Vietnam (BR-PPL-023, input to Payroll's 183-day report). */
    public record PresencePeriod(UUID presencePeriodId, LocalDate entryDate, LocalDate exitDate) {
    }

    /**
     * {@code people.PersonalDataChanged} (OUT-15): approved personal data of a person changed.
     *
     * @param changedFields  minimised field names, e.g. {@code family_name}, {@code tax_code}, {@code address.PERMANENT},
     *                       {@code tax_residency}, {@code vn_presence}
     * @param nationalityCode the nationality in effect, sent when it changed (P1)
     * @param taxResidency   when {@code tax_residency} changed: the approved status row
     * @param presencePeriod when {@code vn_presence} changed: the period
     * @param reason         PERSON_CREATED, PERSON_UPDATED, IDENTIFIERS_UPDATED, ADDRESS_CHANGED, TAX_RESIDENCY_APPROVED,
     *                       PRESENCE_CHANGED, PERSONS_MERGED
     */
    public record PersonalDataChanged(UUID tenantId, UUID personId, LocalDate effectiveDate, List<String> changedFields,
            String nationalityCode, TaxResidency taxResidency, PresencePeriod presencePeriod, String reason)
            implements PeopleEvent {

        public PersonalDataChanged {
            changedFields = List.copyOf(changedFields);
        }

        @Override
        public String eventType() {
            return "people.PersonalDataChanged";
        }

        @Override
        public UUID legalEntityId() {
            return null;
        }

        @Override
        public UUID employmentId() {
            return null;
        }
    }

    /**
     * {@code people.SpecialStatusChanged} (OUT-19, BR-PPL-021): a special status was recorded, changed or cancelled.
     * Carries the type and the period only (T&A: reduced hours, OT / night-work bans; Payroll; separation guard);
     * details (disability degree, medical data, membership numbers) never travel.
     *
     * @param change CREATED, CHANGED, CANCELLED, DERIVED (minor / elderly from the birth date)
     */
    public record SpecialStatusChanged(UUID tenantId, UUID personId, UUID employmentId, UUID specialStatusId,
            String statusType, LocalDate validFrom, LocalDate validTo, String change) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.SpecialStatusChanged";
        }

        @Override
        public UUID legalEntityId() {
            return null;
        }

        @Override
        public LocalDate effectiveDate() {
            return validFrom;
        }
    }

    /**
     * {@code people.PersonsMerged} (US-PPL-004; not in catalogue v1.3, proposed name): the duplicate person was merged
     * into the surviving one; every People child row now points to {@code personId}. Consumers holding the merged id
     * (Core user links, Payroll projections) re-point it.
     *
     * @param movedRows number of re-pointed rows per {@code hr} table
     */
    public record PersonsMerged(UUID tenantId, UUID personId, UUID mergedPersonId, LocalDate effectiveDate,
            Map<String, Integer> movedRows) implements PeopleEvent {

        public PersonsMerged {
            movedRows = Map.copyOf(movedRows);
        }

        @Override
        public String eventType() {
            return "people.PersonsMerged";
        }

        @Override
        public UUID legalEntityId() {
            return null;
        }

        @Override
        public UUID employmentId() {
            return null;
        }
    }
}
