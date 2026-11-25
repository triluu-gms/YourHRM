package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.Locations;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * {@link Locations}: the versions of {@code core.location_version} with their anchor (BR-CORE-005). Each read runs in
 * its OWN transaction: consumers call it from {@code core.LocationChanged} listeners after Core's commit, where joining
 * the finished transaction would run without the tenant settings (RLS would hide every row).
 */
@Service
class LocationDirectory implements Locations {

    private final JdbcClient jdbc;

    LocationDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Version> versions(UUID locationId) {
        return jdbc.sql("""
                        select v.id, l.id as location_id, l.legal_entity_id, l.code, v.name, v.time_zone,
                               v.admin_unit_code, v.is_remote_area, v.latitude, v.longitude, v.geofence_radius_m,
                               case when l.closed_from is not null and v.valid_from >= l.closed_from then 'CLOSED'
                                    else 'ACTIVE' end as status,
                               v.valid_from, v.valid_to, l.version
                          from core.location l
                          join core.location_version v on v.tenant_id = l.tenant_id and v.location_id = l.id
                         where l.tenant_id = :t and l.id = :id
                         order by v.valid_from""")
                .param("t", TenantContext.current().tenantId()).param("id", locationId)
                .query((rs, n) -> new Version(rs.getObject("id", UUID.class),
                        rs.getObject("location_id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                        rs.getString("code"), rs.getString("name"), rs.getString("time_zone"),
                        rs.getString("admin_unit_code"), rs.getBoolean("is_remote_area"),
                        rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                        (Integer) rs.getObject("geofence_radius_m"), rs.getString("status"),
                        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                        rs.getLong("version")))
                .list();
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<UUID> locationIds() {
        return jdbc.sql("select id from core.location where tenant_id = :t order by id")
                .param("t", TenantContext.current().tenantId()).query(UUID.class).list();
    }
}
