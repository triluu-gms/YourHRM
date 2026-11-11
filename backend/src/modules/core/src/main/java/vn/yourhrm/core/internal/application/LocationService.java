package vn.yourhrm.core.internal.application;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.CoreEvents.LocationChanged;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.domain.LocationStatus;
import vn.yourhrm.core.internal.domain.LocationType;
import vn.yourhrm.core.internal.domain.TaxCode;
import vn.yourhrm.core.internal.infra.EffectiveDatedVersions;
import vn.yourhrm.core.internal.infra.EffectiveDatedVersions.Removal;
import vn.yourhrm.core.internal.infra.EffectiveDatedVersions.Slot;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Branches / work locations (BR-CORE-005, US-CORE-007): each belongs to exactly one legal entity for its whole
 * life, and its attributes are effective-dated ({@code core.location_version}). The min-wage region is NOT stored:
 * it is resolved from {@code admin_unit_code} through the Legal Parameter Service (BR-CORE-006, DDL v0.4 M2).
 * Geo-fence fields are optional and only used by P2 check-in (OD-9). A location is in scope for a user through
 * either a LOCATION grant or a LEGAL_ENTITY grant on its legal entity.
 */
@Service
public class LocationService {

    private static final ScopePredicate.Columns SCOPE_COLUMNS =
            new ScopePredicate.Columns("l.legal_entity_id", null, "l.id", null);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final EffectiveDatedVersions versions;
    private final MinWageRegionService regions;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;
    private final BusinessClock clock;

    public LocationService(JdbcClient jdbc, AuthorizationService authz, EffectiveDatedVersions versions,
            MinWageRegionService regions, ApplicationEventPublisher events, AuditLog audit, BusinessClock clock) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.versions = versions;
        this.regions = regions;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** The full attribute set of one version (PUT semantics). */
    public record Attributes(String name, LocationType locationType, String adminUnitCode, String addressLine,
            Boolean remoteArea, String timeZone, BigDecimal latitude, BigDecimal longitude, Integer geofenceRadiusM,
            String dependentTaxCode) {
    }

    public record VersionView(UUID versionId, LocalDate validFrom, LocalDate validTo, String name,
            LocationType locationType, String adminUnitCode, String addressLine, boolean remoteArea, String timeZone,
            BigDecimal latitude, BigDecimal longitude, Integer geofenceRadiusM, String dependentTaxCode) {
    }

    /**
     * Anchor + the version shown for the requested date. {@code status} is as of that date (CLOSED from
     * {@code closedFrom} on); {@code version} is the optimistic-lock token for every write.
     */
    public record LocationView(UUID id, String code, UUID legalEntityId, LocationStatus status, LocalDate closedFrom,
            int version, VersionView current) {
    }

    @Transactional(readOnly = true)
    public List<LocationView> list(LocalDate asOf, UUID legalEntityId) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        SqlFragment scope = authz.scopeFor(CorePermissions.LOCATION_VIEW, date).toSql(SCOPE_COLUMNS, "s");
        return jdbc.sql(SELECT + " where l.tenant_id = :t and (cast(:le as uuid) is null or l.legal_entity_id = :le)"
                        + " and " + scope.sql() + " order by l.code")
                .param("t", TenantContext.current().tenantId()).param("d", date).param("le", legalEntityId)
                .params(scope.params())
                .query((rs, n) -> map(rs)).list();
    }

    @Transactional(readOnly = true)
    public LocationView get(UUID id, LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        assertCan(CorePermissions.LOCATION_VIEW, id, date);
        return find(id, date).orElseThrow(() -> ApiException.notFound("Location", id));
    }

    @Transactional(readOnly = true)
    public List<VersionView> history(UUID id) {
        assertCan(CorePermissions.LOCATION_VIEW, id, clock.today());
        List<VersionView> history = jdbc.sql("select * from core.location_version v"
                        + " where v.tenant_id = :t and v.location_id = :id order by v.valid_from")
                .param("t", TenantContext.current().tenantId()).param("id", id)
                .query((rs, n) -> mapVersion(rs)).list();
        if (history.isEmpty()) {
            throw ApiException.notFound("Location", id);
        }
        return history;
    }

    @Transactional
    public LocationView create(String code, UUID legalEntityId, LocalDate validFrom, Attributes a) {
        TenantContext ctx = TenantContext.current();
        UUID tenant = ctx.tenantId();
        LocalDate from = validFrom == null ? clock.today() : validFrom;
        // Creating a location is allowed to whoever may create locations in that legal entity (LE or TENANT grant).
        if (!authz.can(CorePermissions.LOCATION_CREATE, new AuthorizationTarget.LegalEntity(legalEntityId), from)) {
            throw ApiException.notFound("Legal entity", legalEntityId);
        }
        String leStatus = jdbc.sql("select status from core.legal_entity where tenant_id = :t and id = :id for share")
                .param("t", tenant).param("id", legalEntityId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Legal entity", legalEntityId));
        if ("DISSOLVED".equals(leStatus)) {
            throw ApiException.unprocessable("legal-entity-dissolved", "Cannot add a location to a dissolved legal entity");
        }
        if (jdbc.sql("select exists (select 1 from core.location where tenant_id = :t and code = :c)")
                .param("t", tenant).param("c", code).query(Boolean.class).single()) {
            throw ApiException.conflict("location-code-taken", "A location with code " + code + " already exists");
        }
        UUID id = Ids.newId();
        Attributes valid = validate(tenant, id, legalEntityId, from, a);
        jdbc.sql("""
                        insert into core.location (tenant_id, id, legal_entity_id, code, status, created_by, updated_by)
                        values (:t, :id, :le, :code, 'ACTIVE', :actor, :actor)""")
                .param("t", tenant).param("id", id).param("le", legalEntityId).param("code", code)
                .param("actor", ctx.userAccountId())
                .update();
        insertVersion(tenant, id, from, null, valid);
        events.publishEvent(new LocationChanged(tenant, id, legalEntityId, "CREATED", from));
        regions.locationAddressChanged(id, legalEntityId, from, null, valid.adminUnitCode(), true);
        return find(id, from).orElseThrow();
    }

    /** Applies {@code a} from {@code effectiveFrom}: correction of the version starting then, or a split. */
    @Transactional
    public LocationView changeFrom(UUID id, LocalDate effectiveFrom, Attributes a, int expectedVersion) {
        UUID tenant = TenantContext.current().tenantId();
        assertCan(CorePermissions.LOCATION_UPDATE, id, effectiveFrom);
        versions.checkAndBump(EffectiveDatedVersions.LOCATION, tenant, id, expectedVersion,
                TenantContext.current().userAccountId(), "Location " + id);
        Slot slot = versions.slotFor(EffectiveDatedVersions.LOCATION, tenant, id, effectiveFrom);
        UUID legalEntityId = legalEntityOf(tenant, id);
        Attributes valid = validate(tenant, id, legalEntityId, effectiveFrom, a);
        String previousAdminUnit = adminUnitOf(tenant, slot.covering().id());
        if (slot.split()) {
            versions.closeAt(EffectiveDatedVersions.LOCATION, tenant, slot.covering().id(), effectiveFrom);
            insertVersion(tenant, id, effectiveFrom, slot.covering().validTo(), valid);
        } else {
            jdbc.sql("""
                            update core.location_version
                               set name = :name, location_type = :type, admin_unit_code = :au, address_line = :addr,
                                   is_remote_area = :remote, time_zone = :tz, latitude = :lat, longitude = :lon,
                                   geofence_radius_m = :radius, dependent_tax_code = :dtc, updated_by = :actor,
                                   version = version + 1
                             where tenant_id = :t and id = :vid""")
                    .param("vid", slot.covering().id()).params(attributeParams(tenant, valid))
                    .update();
        }
        events.publishEvent(new LocationChanged(tenant, id, legalEntityId, "CHANGED", effectiveFrom));
        regions.locationAddressChanged(id, legalEntityId, effectiveFrom, previousAdminUnit, valid.adminUnitCode(), false);
        return find(id, effectiveFrom).orElseThrow();
    }

    /**
     * Closes a location from {@code effectiveFrom} (US-CORE-007, like org units): the version covering that date ends
     * the day before, the anchor becomes CLOSED (final) with {@code closed_from}. Refused when a version starts on or
     * after that date ({@code future-versions-exist}: remove the planned version first) and on the first day of the
     * location's history. Required before its legal entity can be dissolved.
     */
    @Transactional
    public LocationView close(UUID id, LocalDate effectiveFrom, int expectedVersion) {
        TenantContext ctx = TenantContext.current();
        UUID tenant = ctx.tenantId();
        assertCan(CorePermissions.LOCATION_UPDATE, id, effectiveFrom);
        versions.checkAndBump(EffectiveDatedVersions.LOCATION, tenant, id, expectedVersion, ctx.userAccountId(),
                "Location " + id);
        String code = jdbc.sql("select code from core.location where tenant_id = :t and id = :id and status = 'ACTIVE'")
                .param("t", tenant).param("id", id).query(String.class).optional()
                .orElseThrow(() -> ApiException.unprocessable("invalid-status-transition", "Location " + id
                        + " is already closed"));
        Slot slot = versions.slotFor(EffectiveDatedVersions.LOCATION, tenant, id, effectiveFrom);
        boolean laterVersions = jdbc.sql("""
                        select exists (select 1 from core.location_version
                                        where tenant_id = :t and location_id = :id and valid_from > :d)""")
                .param("t", tenant).param("id", id).param("d", effectiveFrom).query(Boolean.class).single();
        if (laterVersions || (!slot.split() && hasVersionEndingAt(tenant, id, effectiveFrom))) {
            throw ApiException.conflict("future-versions-exist", "Location " + code + " has changes planned from "
                    + effectiveFrom + " on; remove them before closing it from that date");
        }
        if (!slot.split()) {
            throw ApiException.unprocessable("invalid-close-date", "Location " + code + " starts on " + effectiveFrom
                    + "; it can only be closed after its first day");
        }
        versions.closeAt(EffectiveDatedVersions.LOCATION, tenant, slot.covering().id(), effectiveFrom);
        jdbc.sql("""
                        update core.location set status = 'CLOSED', closed_from = :d, updated_by = :actor
                         where tenant_id = :t and id = :id""")
                .param("d", effectiveFrom).param("actor", ctx.userAccountId()).param("t", tenant).param("id", id)
                .update();
        LocationView current = find(id, effectiveFrom).orElseThrow();
        events.publishEvent(new LocationChanged(tenant, id, current.legalEntityId(), "CLOSED", effectiveFrom));
        return current;
    }

    /**
     * Removes a planned (not yet effective) version; the version before it is extended over the freed period
     * (the min-wage region is re-diffed for the address that now applies). Audited as {@code REMOVE_PLANNED_VERSION}.
     */
    @Transactional
    public LocationView removePlannedVersion(UUID id, UUID versionId, int expectedVersion) {
        UUID tenant = TenantContext.current().tenantId();
        LocalDate today = clock.today();
        assertCan(CorePermissions.LOCATION_UPDATE, id, today);
        versions.checkAndBump(EffectiveDatedVersions.LOCATION, tenant, id, expectedVersion,
                TenantContext.current().userAccountId(), "Location " + id);
        String removedAdminUnit = jdbc.sql(
                        "select admin_unit_code from core.location_version where tenant_id = :t and location_id = :l and id = :id")
                .param("t", tenant).param("l", id).param("id", versionId)
                .query((rs, n) -> Optional.ofNullable(rs.getString(1))).optional().flatMap(o -> o).orElse(null);
        Removal removal = versions.removePlanned(EffectiveDatedVersions.LOCATION, tenant, id, versionId, today);
        UUID legalEntityId = legalEntityOf(tenant, id);
        audit.record(new BusinessAction("REMOVE_PLANNED_VERSION", "core", "location", id, legalEntityId,
                Map.of("versionId", versionId.toString(), "validFrom", removal.removed().validFrom().toString())));
        events.publishEvent(new LocationChanged(tenant, id, legalEntityId, "VERSION_REMOVED", removal.removed().validFrom()));
        regions.locationAddressChanged(id, legalEntityId, removal.removed().validFrom(), removedAdminUnit,
                adminUnitOf(tenant, removal.extended().id()), false);
        return find(id, removal.removed().validFrom()).orElseThrow();
    }

    private boolean hasVersionEndingAt(UUID tenant, UUID id, LocalDate date) {
        return jdbc.sql("""
                        select exists (select 1 from core.location_version
                                        where tenant_id = :t and location_id = :id and valid_to = :d)""")
                .param("t", tenant).param("id", id).param("d", date).query(Boolean.class).single();
    }

    private Attributes validate(UUID tenant, UUID id, UUID legalEntityId, LocalDate validFrom, Attributes a) {
        // The legal entity must exist on that date: a location cannot predate (or outlive) its employer's history.
        String leTaxCode = jdbc.sql("""
                        select tax_code from core.legal_entity_version
                         where tenant_id = :t and legal_entity_id = :le and valid_during @> cast(:d as date)""")
                .param("t", tenant).param("le", legalEntityId).param("d", validFrom)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.unprocessable("legal-entity-not-in-effect",
                        "The legal entity has no data in effect on " + validFrom));
        if (a.adminUnitCode() != null && !jdbc.sql("""
                        select exists (select 1 from core.admin_unit where admin_unit_code = :c
                                          and valid_from <= :d and (valid_to is null or valid_to > :d))""")
                .param("c", a.adminUnitCode()).param("d", validFrom).query(Boolean.class).single()) {
            throw ApiException.unprocessable("unknown-admin-unit",
                    "Administrative unit " + a.adminUnitCode() + " does not exist or is not valid on " + validFrom);
        }
        String timeZone = a.timeZone() == null ? "Asia/Ho_Chi_Minh" : a.timeZone();
        try {
            ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            throw ApiException.unprocessable("invalid-time-zone", "Unknown time zone: " + timeZone);
        }
        validateGeo(a);
        String dependentTaxCode = null;
        if (a.dependentTaxCode() != null && !a.dependentTaxCode().isBlank()) {
            TaxCode dtc = TaxCode.parse(a.dependentTaxCode());
            // [verify-legal] A dependent unit's MST is the parent's 10-digit MST plus a 3-digit suffix.
            if (!dtc.isDependentUnit() || !dtc.value().startsWith(leTaxCode.substring(0, 10))) {
                throw ApiException.unprocessable("invalid-dependent-tax-code",
                        "A dependent tax code must be the legal entity's MST " + leTaxCode.substring(0, 10)
                                + " followed by -NNN, got: " + dtc.value());
            }
            if (jdbc.sql("""
                            select exists (select 1 from core.location_version
                                            where tenant_id = :t and dependent_tax_code = :dtc and location_id <> :id)""")
                    .param("t", tenant).param("dtc", dtc.value()).param("id", id).query(Boolean.class).single()) {
                throw ApiException.conflict("dependent-tax-code-taken",
                        "Dependent tax code " + dtc.value() + " is already used by another location");
            }
            dependentTaxCode = dtc.value();
        }
        return new Attributes(a.name(), a.locationType(), a.adminUnitCode(), a.addressLine(),
                a.remoteArea() != null && a.remoteArea(), timeZone, a.latitude(), a.longitude(), a.geofenceRadiusM(),
                dependentTaxCode);
    }

    private static void validateGeo(Attributes a) {
        if ((a.latitude() == null) != (a.longitude() == null)) {
            throw ApiException.unprocessable("invalid-geo", "Latitude and longitude go together");
        }
        if (a.latitude() != null && (a.latitude().abs().compareTo(BigDecimal.valueOf(90)) > 0
                || a.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw ApiException.unprocessable("invalid-geo", "Latitude must be in [-90, 90] and longitude in [-180, 180]");
        }
        if (a.geofenceRadiusM() != null && (a.latitude() == null || a.geofenceRadiusM() <= 0)) {
            throw ApiException.unprocessable("invalid-geo", "A geofence radius needs coordinates and must be positive");
        }
    }

    private void insertVersion(UUID tenant, UUID id, LocalDate from, LocalDate to, Attributes a) {
        jdbc.sql("""
                        insert into core.location_version (tenant_id, id, location_id, valid_from, valid_to, name,
                               location_type, admin_unit_code, address_line, is_remote_area, time_zone, latitude,
                               longitude, geofence_radius_m, dependent_tax_code, created_by, updated_by)
                        values (:t, :vid, :id, :from, :to, :name, :type, :au, :addr, :remote, :tz, :lat, :lon, :radius,
                                :dtc, :actor, :actor)""")
                .param("vid", Ids.newId()).param("id", id).param("from", from).param("to", to)
                .params(attributeParams(tenant, a))
                .update();
    }

    private static Map<String, Object> attributeParams(UUID tenant, Attributes a) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", tenant);
        p.put("name", a.name());
        p.put("type", a.locationType().name());
        p.put("au", a.adminUnitCode());
        p.put("addr", a.addressLine());
        p.put("remote", a.remoteArea());
        p.put("tz", a.timeZone());
        p.put("lat", a.latitude());
        p.put("lon", a.longitude());
        p.put("radius", a.geofenceRadiusM());
        p.put("dtc", a.dependentTaxCode());
        p.put("actor", TenantContext.current().userAccountId());
        return p;
    }

    private String adminUnitOf(UUID tenant, UUID versionId) {
        return jdbc.sql("select admin_unit_code from core.location_version where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", versionId).query((rs, n) -> Optional.ofNullable(rs.getString(1))).single().orElse(null);
    }

    private UUID legalEntityOf(UUID tenant, UUID id) {
        return jdbc.sql("select legal_entity_id from core.location where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", id).query(UUID.class).single();
    }

    private void assertCan(String permission, UUID id, LocalDate date) {
        // Out of scope looks the same as missing: no existence oracle across scopes.
        if (!authz.can(permission, new AuthorizationTarget.Location(id), date)) {
            throw ApiException.notFound("Location", id);
        }
    }

    private Optional<LocationView> find(UUID id, LocalDate date) {
        return jdbc.sql(SELECT + " where l.tenant_id = :t and l.id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", id).param("d", date)
                .query((rs, n) -> map(rs)).optional();
    }

    /** Anchor plus the version valid on {@code :d}, else the latest one (same rule as legal entities). */
    private static final String SELECT = """
            select l.id as l_id, l.code as l_code, l.legal_entity_id as l_le,
                   case when l.closed_from is not null and l.closed_from <= cast(:d as date) then 'CLOSED'
                        when l.closed_from is null and l.status = 'CLOSED' then 'CLOSED'
                        else 'ACTIVE' end as l_status,
                   l.closed_from as l_closed_from, l.version as l_version, v.*
              from core.location l
              left join lateral (
                    select * from core.location_version x
                     where x.tenant_id = l.tenant_id and x.location_id = l.id
                     order by (x.valid_during @> cast(:d as date)) desc, x.valid_from desc
                     limit 1) v on true""";

    private static LocationView map(ResultSet rs) throws SQLException {
        return new LocationView(rs.getObject("l_id", UUID.class), rs.getString("l_code"),
                rs.getObject("l_le", UUID.class), LocationStatus.valueOf(rs.getString("l_status")),
                rs.getObject("l_closed_from", LocalDate.class), rs.getInt("l_version"),
                rs.getObject("id", UUID.class) == null ? null : mapVersion(rs));
    }

    private static VersionView mapVersion(ResultSet rs) throws SQLException {
        return new VersionView(rs.getObject("id", UUID.class), rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class), rs.getString("name"),
                LocationType.valueOf(rs.getString("location_type")), rs.getString("admin_unit_code"),
                rs.getString("address_line"), rs.getBoolean("is_remote_area"), rs.getString("time_zone"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                (Integer) rs.getObject("geofence_radius_m"), rs.getString("dependent_tax_code"));
    }
}
