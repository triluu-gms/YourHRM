package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Lookup of Core's org units and positions as of a date (IN-02 / IN-03; US-PPL-033/034). Core's published API has no
 * such lookup yet (only {@code JobCatalogue}, {@code MinWageRegions}), and People's DB role cannot read
 * {@code core.org_unit_version} / {@code core.position_version}. Declared here so People can validate the soft
 * references of an assignment and walk the position hierarchy for position-based managers; it should move to
 * {@code vn.yourhrm.core.api} and be implemented by Core (a Core class cannot implement a People interface without a
 * module cycle).
 *
 * <p>Fallback {@link #NONE}: {@link #available()} is false; org unit and position ids are then accepted unvalidated and
 * a manager cannot be derived from a position (the line manager must be named explicitly).
 */
public interface OrgStructureLookup {

    record OrgUnitRef(UUID id, UUID legalEntityId, UUID locationId) {
    }

    /** {@code reportsToPositionId}: the manager position (next level up), null at the top. */
    record PositionRef(UUID id, UUID orgUnitId, UUID legalEntityId, UUID jobTitleId, UUID reportsToPositionId,
            String status) {
    }

    boolean available();

    /** The org unit version in effect on {@code asOf}; empty when it does not exist then. */
    Optional<OrgUnitRef> orgUnit(UUID orgUnitId, LocalDate asOf);

    /** The position version in effect on {@code asOf}; empty when it does not exist then. */
    Optional<PositionRef> position(UUID positionId, LocalDate asOf);

    /** STUB until Core publishes the lookup. */
    OrgStructureLookup NONE = new OrgStructureLookup() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<OrgUnitRef> orgUnit(UUID orgUnitId, LocalDate asOf) {
            return Optional.empty();
        }

        @Override
        public Optional<PositionRef> position(UUID positionId, LocalDate asOf) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "OrgStructureLookup.NONE (Core org/position lookup not published: references unvalidated)";
        }
    };
}
