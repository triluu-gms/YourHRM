package vn.yourhrm.attendance.internal.infra.location;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.core.api.Locations.Version;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of {@code att.ext_core_location}, T&amp;A's projection of Core's location versions (time zone, remote-area flag;
 * geo-fence for P2). A location is always replaced as a whole with Core's current versions, so a removed or reshaped
 * version never survives. Runs inside the caller's T&amp;A transaction.
 */
@Component
public class LocationStore {

    private static final String DEFAULT_ZONE = "Asia/Ho_Chi_Minh";

    private final AttDb db;

    public LocationStore(AttDb db) {
        this.db = db;
    }

    /** Replaces every projected version of {@code locationId}; returns the number of rows written. */
    public int replace(UUID locationId, List<Version> versions, UUID sourceEventId) {
        UUID t = TenantContext.current().tenantId();
        db.jdbc().sql("delete from att.ext_core_location where tenant_id = :t and location_id = :l")
                .param("t", t).param("l", locationId).update();
        for (Version v : versions) {
            db.jdbc().sql("""
                            insert into att.ext_core_location (tenant_id, id, location_id, legal_entity_id, code, name,
                                   time_zone, admin_unit_code, is_remote_area, latitude, longitude, geofence_radius_m,
                                   status, valid_from, valid_to, source_event_id, source_version, synced_at)
                            values (:t, :id, :l, :le, :code, :name, :tz, :au, :remote, :lat, :lon, :radius, :status,
                                    :vf, :vt, :ev, :sv, now())""")
                    .param("t", t).param("id", v.versionId()).param("l", locationId).param("le", v.legalEntityId())
                    .param("code", v.code()).param("name", v.name())
                    .param("tz", v.timeZone() == null || v.timeZone().isBlank() ? DEFAULT_ZONE : v.timeZone())
                    .param("au", v.adminUnitCode()).param("remote", v.remoteArea()).param("lat", v.latitude())
                    .param("lon", v.longitude()).param("radius", v.geofenceRadiusM()).param("status", v.status())
                    .param("vf", v.validFrom()).param("vt", v.validTo()).param("ev", sourceEventId)
                    .param("sv", v.anchorVersion()).update();
        }
        return versions.size();
    }
}
