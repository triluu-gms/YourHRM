package vn.yourhrm.shared.auth.api;

import java.util.Objects;
import java.util.UUID;

/**
 * What a {@link AuthorizationService#can} check is about (US-CORE-037).
 * Organisational attributes of a target are resolved by Core as of the check date, except for
 * {@link Employee}, whose attributes are owned by People and therefore passed in by the caller.
 */
public sealed interface AuthorizationTarget {

    /** The tenant as a whole (only a TENANT-scoped grant covers it). */
    record Tenant() implements AuthorizationTarget {
    }

    record LegalEntity(UUID id) implements AuthorizationTarget {
        public LegalEntity {
            Objects.requireNonNull(id, "id");
        }
    }

    record OrgUnit(UUID id) implements AuthorizationTarget {
        public OrgUnit {
            Objects.requireNonNull(id, "id");
        }
    }

    record Location(UUID id) implements AuthorizationTarget {
        public Location {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * An employment (People). The denormalised organisational attributes at the check date are supplied by
     * the caller (DESIGN.md §5.3: every employee row carries legal_entity_id / org_unit_id).
     */
    record Employee(UUID employmentId, UUID legalEntityId, UUID orgUnitId, UUID locationId)
            implements AuthorizationTarget {
        public Employee {
            Objects.requireNonNull(employmentId, "employmentId");
        }
    }

    static AuthorizationTarget tenant() {
        return new Tenant();
    }
}
