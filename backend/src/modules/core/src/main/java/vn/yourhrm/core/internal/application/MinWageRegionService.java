package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CoreEvents.LegalEntityRegionChanged;
import vn.yourhrm.core.api.CoreEvents.LocationRegionChanged;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.MinWageRegions;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameterPackPublished;
import vn.yourhrm.shared.common.legal.LegalParameters;
import vn.yourhrm.shared.common.legal.LegalParameters.MapEntry;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Minimum-wage region resolution (BR-CORE-006) and recomputation (BR-CORE-007). Core stores no region and no
 * region map (DDL v0.4 M2): every answer is derived on demand from the address' {@code admin_unit_code} through the
 * Legal Parameter Service, so nothing can go stale; "recompute" means diffing the region before and after an
 * address change or a pack change and publishing the change for Payroll / People (minimum-wage checks).
 */
@Service
public class MinWageRegionService implements MinWageRegions {

    private static final Logger log = LoggerFactory.getLogger(MinWageRegionService.class);

    private final ObjectProvider<LegalParameters> legalParameters;
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;

    public MinWageRegionService(ObjectProvider<LegalParameters> legalParameters, JdbcClient jdbc,
            AuthorizationService authz, ApplicationEventPublisher events, BusinessClock clock) {
        this.legalParameters = legalParameters;
        this.jdbc = jdbc;
        this.authz = authz;
        this.events = events;
        this.clock = clock;
    }

    /** Address of an entity version in effect on a date. */
    private record Address(UUID legalEntityId, UUID locationId, String adminUnitCode) {
    }

    // ---------------------------------------------------------------- public API (P-03)

    @Override
    @Transactional(readOnly = true)
    public Optional<Resolution> forLocation(UUID locationId, LocalDate asOf) {
        return locationAddress(locationId, asOf).map(a -> resolve(a, asOf, null));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Resolution> forLegalEntity(UUID legalEntityId, LocalDate asOf) {
        return legalEntityAddress(legalEntityId, asOf).map(a -> resolve(a, asOf, null));
    }

    @Override
    @Transactional(readOnly = true)
    public int recomputeAfterPackPublished(LegalParameterPackPublished event) {
        if (!event.changedKeys().contains(LegalParameters.MIN_WAGE_REGION_MAP)) {
            return 0;
        }
        UUID tenant = TenantContext.current().tenantId();
        List<LocalDate> dates = event.changedFrom().isEmpty() ? List.of(clock.today()) : event.changedFrom();
        int changes = 0;
        // Open locations, and closed ones whose closing date is still ahead (closure recorded, history not ended yet).
        List<UUID> locations = jdbc.sql("select id from core.location where tenant_id = :t"
                        + " and (status = 'ACTIVE' or closed_from > current_date)")
                .param("t", tenant).query(UUID.class).list();
        for (UUID location : locations) {
            for (LocalDate d : dates) {
                Optional<Address> address = locationAddress(location, d);
                if (address.isEmpty()) {
                    continue;
                }
                Region before = resolve(address.get(), d, event.previousPackVersion()).region();
                Region after = resolve(address.get(), d, event.packVersion()).region();
                if (before != after) {
                    events.publishEvent(new LocationRegionChanged(tenant, location, address.get().legalEntityId(), d,
                            name(before), name(after), event.packVersion(), "LEGAL_PACK"));
                    changes++;
                }
            }
        }
        List<UUID> legalEntities = jdbc.sql(
                        "select id from core.legal_entity where tenant_id = :t and status <> 'DISSOLVED'")
                .param("t", tenant).query(UUID.class).list();
        for (UUID le : legalEntities) {
            for (LocalDate d : dates) {
                Optional<Address> address = legalEntityAddress(le, d);
                if (address.isEmpty()) {
                    continue;
                }
                Region before = resolve(address.get(), d, event.previousPackVersion()).region();
                Region after = resolve(address.get(), d, event.packVersion()).region();
                if (before != after) {
                    events.publishEvent(new LegalEntityRegionChanged(tenant, le, d, name(before), name(after),
                            event.packVersion(), "LEGAL_PACK"));
                    changes++;
                }
            }
        }
        log.info("Pack {} -> {}: {} minimum-wage region change(s) in tenant {}", event.previousPackVersion(),
                event.packVersion(), changes, tenant);
        return changes;
    }

    // ---------------------------------------------------------------- user-facing lookups (scoped)

    @Transactional(readOnly = true)
    public Resolution locationRegion(UUID locationId, LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        if (!authz.can(CorePermissions.LOCATION_VIEW, new AuthorizationTarget.Location(locationId), date)) {
            throw ApiException.notFound("Location", locationId);
        }
        return forLocation(locationId, date).orElseThrow(() -> notInEffect("Location", locationId, date));
    }

    @Transactional(readOnly = true)
    public Resolution legalEntityRegion(UUID legalEntityId, LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        if (!authz.can(CorePermissions.LEGAL_ENTITY_VIEW, new AuthorizationTarget.LegalEntity(legalEntityId), date)) {
            throw ApiException.notFound("Legal entity", legalEntityId);
        }
        return forLegalEntity(legalEntityId, date).orElseThrow(() -> notInEffect("Legal entity", legalEntityId, date));
    }

    // ---------------------------------------------------------------- address-change hooks (BR-CORE-007)

    /**
     * Called by the location write paths after the change from {@code effectiveFrom} was written, with the
     * administrative unit in effect on that date before the change ({@code null} for a new location). Never fails
     * the write: without a Legal Parameter Service the region simply cannot be evaluated yet.
     */
    void locationAddressChanged(UUID locationId, UUID legalEntityId, LocalDate effectiveFrom, String previousAdminUnit,
            String newAdminUnit, boolean created) {
        if (!created && Objects.equals(previousAdminUnit, newAdminUnit)) {
            return;
        }
        LegalParameters lps = legalParameters.getIfAvailable();
        if (lps == null) {
            log.warn("No Legal Parameter Service: region of location {} not evaluated", locationId);
            return;
        }
        Scope scope = new Scope(legalEntityId, locationId);
        Optional<MapEntry> before = lookup(lps, previousAdminUnit, scope, effectiveFrom, null);
        Optional<MapEntry> after = lookup(lps, newAdminUnit, scope, effectiveFrom, null);
        Region from = before.map(MinWageRegionService::region).orElse(null);
        Region to = after.map(MinWageRegionService::region).orElse(null);
        if (created || from != to) {
            long pack = after.or(() -> before).map(MapEntry::packVersion).orElse(0L);
            events.publishEvent(new LocationRegionChanged(TenantContext.current().tenantId(), locationId,
                    legalEntityId, effectiveFrom, created ? null : name(from), name(to), pack,
                    created ? "CREATED" : "ADDRESS"));
        }
    }

    /** Same as {@link #locationAddressChanged} for a legal entity's registered address. */
    void legalEntityAddressChanged(UUID legalEntityId, LocalDate effectiveFrom, String previousAdminUnit,
            String newAdminUnit) {
        if (Objects.equals(previousAdminUnit, newAdminUnit)) {
            return;
        }
        LegalParameters lps = legalParameters.getIfAvailable();
        if (lps == null) {
            log.warn("No Legal Parameter Service: region of legal entity {} not evaluated", legalEntityId);
            return;
        }
        Scope scope = new Scope(legalEntityId, null);
        Optional<MapEntry> before = lookup(lps, previousAdminUnit, scope, effectiveFrom, null);
        Optional<MapEntry> after = lookup(lps, newAdminUnit, scope, effectiveFrom, null);
        Region from = before.map(MinWageRegionService::region).orElse(null);
        Region to = after.map(MinWageRegionService::region).orElse(null);
        if (from != to) {
            long pack = after.or(() -> before).map(MapEntry::packVersion).orElse(0L);
            events.publishEvent(new LegalEntityRegionChanged(TenantContext.current().tenantId(), legalEntityId,
                    effectiveFrom, name(from), name(to), pack, "ADDRESS"));
        }
    }

    // ---------------------------------------------------------------- internals

    private Resolution resolve(Address a, LocalDate asOf, Long packVersion) {
        LegalParameters lps = legalParameters.getIfAvailable();
        if (lps == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "legal-parameters-unavailable",
                    "The Legal Parameter Service is not available; the minimum-wage region cannot be determined");
        }
        Optional<MapEntry> entry = lookup(lps, a.adminUnitCode(), new Scope(a.legalEntityId(), a.locationId()), asOf,
                packVersion);
        return new Resolution(a.legalEntityId(), a.locationId(), asOf, a.adminUnitCode(),
                entry.map(MinWageRegionService::region).orElse(null), entry.map(MapEntry::packVersion).orElse(null),
                entry.map(MapEntry::overridden).orElse(false));
    }

    private static Optional<MapEntry> lookup(LegalParameters lps, String adminUnit, Scope scope, LocalDate asOf,
            Long packVersion) {
        if (adminUnit == null) {
            return Optional.empty();
        }
        return packVersion == null
                ? lps.mapEntry(LegalParameters.MIN_WAGE_REGION_MAP, adminUnit, scope, asOf)
                : lps.mapEntry(LegalParameters.MIN_WAGE_REGION_MAP, adminUnit, scope, asOf, packVersion);
    }

    private static Region region(MapEntry entry) {
        Object value = entry.value().get("region");
        try {
            return Region.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Legal pack " + entry.packVersion() + " maps to an unknown region: " + value, e);
        }
    }

    private static String name(Region region) {
        return region == null ? null : region.name();
    }

    private Optional<Address> locationAddress(UUID locationId, LocalDate date) {
        return jdbc.sql("""
                        select l.legal_entity_id, v.admin_unit_code
                          from core.location l
                          join core.location_version v on v.tenant_id = l.tenant_id and v.location_id = l.id
                         where l.tenant_id = :t and l.id = :id and v.valid_during @> cast(:d as date)""")
                .param("t", TenantContext.current().tenantId()).param("id", locationId).param("d", date)
                .query((rs, n) -> new Address(rs.getObject(1, UUID.class), locationId, rs.getString(2)))
                .optional();
    }

    private Optional<Address> legalEntityAddress(UUID legalEntityId, LocalDate date) {
        return jdbc.sql("""
                        select admin_unit_code from core.legal_entity_version
                         where tenant_id = :t and legal_entity_id = :id and valid_during @> cast(:d as date)""")
                .param("t", TenantContext.current().tenantId()).param("id", legalEntityId).param("d", date)
                .query((rs, n) -> new Address(legalEntityId, null, rs.getString(1)))
                .optional();
    }

    private static ApiException notInEffect(String what, UUID id, LocalDate date) {
        return ApiException.unprocessable("no-version-at-date", what + " " + id + " has no data in effect on " + date);
    }
}
