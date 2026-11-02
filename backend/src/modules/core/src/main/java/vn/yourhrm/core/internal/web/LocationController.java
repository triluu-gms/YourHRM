package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.MinWageRegions.Resolution;
import vn.yourhrm.core.internal.application.MinWageRegionService;
import vn.yourhrm.core.internal.application.LocationService;
import vn.yourhrm.core.internal.application.LocationService.Attributes;
import vn.yourhrm.core.internal.application.LocationService.LocationView;
import vn.yourhrm.core.internal.application.LocationService.VersionView;
import vn.yourhrm.core.internal.domain.LocationType;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Branches / work locations (BR-CORE-005, US-CORE-007). Scope: a LOCATION grant on the location or a LEGAL_ENTITY
 * grant on its legal entity; creating needs {@code core.location.create} on the target legal entity.
 */
@RestController
@RequestMapping("/api/v1/core/locations")
class LocationController {

    private final LocationService locations;
    private final MinWageRegionService regions;

    LocationController(LocationService locations, MinWageRegionService regions) {
        this.locations = locations;
        this.regions = regions;
    }

    record AttributesRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull LocationType locationType,
            @Size(max = 16) String adminUnitCode,
            @Size(max = 500) String addressLine,
            Boolean remoteArea,
            String timeZone,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer geofenceRadiusM,
            String dependentTaxCode) {

        Attributes toAttributes() {
            return new Attributes(name, locationType, adminUnitCode, addressLine, remoteArea, timeZone, latitude,
                    longitude, geofenceRadiusM, dependentTaxCode);
        }
    }

    record CreateLocationRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,32}$") String code,
            @NotNull UUID legalEntityId,
            LocalDate validFrom,
            @NotNull @Valid AttributesRequest attributes) {
    }

    record ChangeLocationRequest(@NotNull LocalDate effectiveFrom, @NotNull @Valid AttributesRequest attributes) {
    }

    record CloseLocationRequest(@NotNull Integer version) {
    }

    @GetMapping
    @RequiresPermission(CorePermissions.LOCATION_VIEW)
    public List<LocationView> list(
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId) {
        return locations.list(asOf, legalEntityId);
    }

    @GetMapping("/{locationId}")
    @RequiresPermission(CorePermissions.LOCATION_VIEW)
    public LocationView get(@PathVariable UUID locationId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return locations.get(locationId, asOf);
    }

    @GetMapping("/{locationId}/versions")
    @RequiresPermission(CorePermissions.LOCATION_VIEW)
    public List<VersionView> history(@PathVariable UUID locationId) {
        return locations.history(locationId);
    }

    /** Minimum-wage region in effect on {@code asOf} (BR-CORE-006), resolved through the Legal Parameter Service. */
    @GetMapping("/{locationId}/min-wage-region")
    @RequiresPermission(CorePermissions.LOCATION_VIEW)
    public Resolution minWageRegion(@PathVariable UUID locationId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return regions.locationRegion(locationId, asOf);
    }

    @PostMapping
    @RequiresPermission(CorePermissions.LOCATION_CREATE)
    public ResponseEntity<LocationView> create(@Valid @RequestBody CreateLocationRequest req) {
        LocationView created = locations.create(req.code(), req.legalEntityId(), req.validFrom(),
                req.attributes().toAttributes());
        return ResponseEntity.created(URI.create("/api/v1/core/locations/" + created.id())).body(created);
    }

    @PostMapping("/{locationId}/versions")
    @RequiresPermission(CorePermissions.LOCATION_UPDATE)
    public LocationView changeFrom(@PathVariable UUID locationId, @Valid @RequestBody ChangeLocationRequest req) {
        return locations.changeFrom(locationId, req.effectiveFrom(), req.attributes().toAttributes());
    }

    @PostMapping("/{locationId}/close")
    @RequiresPermission(CorePermissions.LOCATION_UPDATE)
    public LocationView close(@PathVariable UUID locationId, @Valid @RequestBody CloseLocationRequest req) {
        return locations.close(locationId, req.version());
    }
}
