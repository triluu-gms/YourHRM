package vn.yourhrm.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read API of Core's work locations for the modules that keep a projection of them (BR-CORE-005; T&amp;A's
 * {@code att.ext_core_location}, fed on {@code core.LocationChanged}). Evaluated in the current {@code TenantContext};
 * no user permission check (system-to-system, like {@link JobCatalogue}). The geo-fence fields are P2 check-in data.
 */
public interface Locations {

    /**
     * One effective-dated version of a location.
     *
     * @param versionId     {@code core.location_version.id}
     * @param status        ACTIVE, or CLOSED for a version from the location's closing date on
     * @param validTo       exclusive, null = open-ended
     * @param anchorVersion optimistic-lock version of the location anchor (changes with every write)
     */
    record Version(UUID versionId, UUID locationId, UUID legalEntityId, String code, String name, String timeZone,
            String adminUnitCode, boolean remoteArea, BigDecimal latitude, BigDecimal longitude,
            Integer geofenceRadiusM, String status, LocalDate validFrom, LocalDate validTo, long anchorVersion) {
    }

    /** Every version of the location, oldest first; empty when the location does not exist in the tenant. */
    List<Version> versions(UUID locationId);

    /** Ids of every location of the tenant (projection re-sync). */
    List<UUID> locationIds();
}
