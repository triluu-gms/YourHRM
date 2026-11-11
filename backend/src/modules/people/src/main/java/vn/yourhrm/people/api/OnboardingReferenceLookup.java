package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-side SPI (US-PPL-089/090): resolves Core catalogue codes used in the opening-balance templates
 * (job title, position, grade, cost centre) to their ids as of a date. Core's import engine only offers the lookups
 * LEGAL_ENTITY, LOCATION, ORG_UNIT, ORG_UNIT_TYPE and CODE_LIST, Core's {@code JobCatalogue} looks up by id only, and
 * People's DB role cannot read those tenant tables. Should move to {@code vn.yourhrm.core.api} (or become import
 * lookups JOB_TITLE / POSITION / GRADE / COST_CENTRE) and be implemented by Core.
 *
 * <p>Fallback {@link #NONE}: nothing resolves by code; the template then accepts the record id (UUID) in those
 * columns, which the assignment stream validates as for any other assignment.
 */
public interface OnboardingReferenceLookup {

    /** {@code kind}: JOB_TITLE, POSITION, GRADE or COST_CENTRE. */
    Optional<UUID> resolve(String kind, String code, UUID legalEntityId, LocalDate asOf);

    /** STUB until Core publishes code lookups. */
    OnboardingReferenceLookup NONE = new OnboardingReferenceLookup() {
        @Override
        public Optional<UUID> resolve(String kind, String code, UUID legalEntityId, LocalDate asOf) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "OnboardingReferenceLookup.NONE (stub: ids only)";
        }
    };
}
