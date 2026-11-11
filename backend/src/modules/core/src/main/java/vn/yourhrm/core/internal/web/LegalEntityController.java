package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.MinWageRegions.Resolution;
import vn.yourhrm.core.internal.application.MinWageRegionService;
import vn.yourhrm.core.internal.application.LegalEntityService;
import vn.yourhrm.core.internal.application.LegalEntityService.Attributes;
import vn.yourhrm.core.internal.application.LegalEntityService.LegalEntityView;
import vn.yourhrm.core.internal.application.LegalEntityService.Usage;
import vn.yourhrm.core.internal.application.LegalEntityService.VersionView;
import vn.yourhrm.core.internal.domain.LegalEntityStatus;
import vn.yourhrm.core.internal.domain.PitDeclarationFrequency;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Legal entities (BR-CORE-001..004, US-CORE-002/003/004). Reads are filtered by data scope; creating a legal
 * entity is tenant administration (TENANT-scoped grant) limited by the subscription plan; changes of an existing
 * entity need {@code core.legal_entity.update} on that entity.
 */
@RestController
@RequestMapping("/api/v1/core/legal-entities")
class LegalEntityController {

    private final LegalEntityService legalEntities;
    private final MinWageRegionService regions;

    LegalEntityController(LegalEntityService legalEntities, MinWageRegionService regions) {
        this.legalEntities = legalEntities;
        this.regions = regions;
    }

    /** The statutory attribute set. The MST format itself is checked by the service (error code invalid-tax-code). */
    record AttributesRequest(
            @NotBlank @Size(max = 300) String legalNameVi,
            @Size(max = 300) String legalNameEn,
            @Size(max = 100) String shortName,
            @NotBlank String taxCode,
            @Size(max = 32) String siUnitCode,
            @Size(max = 64) String businessRegNo,
            LocalDate businessRegDate,
            @Size(max = 200) String businessRegIssuer,
            @Size(max = 20) String phone,
            @Size(max = 254) String email,
            @Size(max = 16) String adminUnitCode,
            @Size(max = 500) String addressLine,
            @Size(max = 200) String legalRepName,
            @Size(max = 200) String legalRepTitle,
            @Min(1) @Max(12) Integer fiscalYearStartMonth,
            @Size(max = 32) String taxAuthorityCode,
            PitDeclarationFrequency pitDeclarationFrequency,
            BigDecimal oaOdRate,
            @Size(max = 300) String oaOdDecisionRef,
            UUID oaOdDecisionDocId) {

        Attributes toAttributes() {
            return new Attributes(legalNameVi, legalNameEn, shortName, taxCode, siUnitCode, businessRegNo,
                    businessRegDate, businessRegIssuer, phone, email, adminUnitCode, addressLine, legalRepName,
                    legalRepTitle, fiscalYearStartMonth, taxAuthorityCode, pitDeclarationFrequency, oaOdRate,
                    oaOdDecisionRef, oaOdDecisionDocId);
        }
    }

    record CreateLegalEntityRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,32}$") String code,
            LegalEntityStatus status,
            LocalDate validFrom,
            @NotNull @Valid AttributesRequest attributes) {
    }

    /** {@code version} = the entity's version as read (optimistic lock over its whole history). */
    record ChangeLegalEntityRequest(@NotNull LocalDate effectiveFrom, @NotNull @Valid AttributesRequest attributes,
            @NotNull Integer version) {
    }

    record ChangeStatusRequest(@NotNull LegalEntityStatus status, @NotNull Integer version) {
    }

    @GetMapping
    @RequiresPermission(CorePermissions.LEGAL_ENTITY_VIEW)
    public List<LegalEntityView> list(
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "status", required = false) LegalEntityStatus status) {
        return legalEntities.list(asOf, status);
    }

    @GetMapping("/usage")
    @RequiresPermission(value = CorePermissions.LEGAL_ENTITY_CREATE, tenantWide = true)
    public Usage usage() {
        return legalEntities.usage();
    }

    @GetMapping("/{legalEntityId}")
    @RequiresPermission(CorePermissions.LEGAL_ENTITY_VIEW)
    public LegalEntityView get(@PathVariable UUID legalEntityId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return legalEntities.get(legalEntityId, asOf);
    }

    @GetMapping("/{legalEntityId}/versions")
    @RequiresPermission(CorePermissions.LEGAL_ENTITY_VIEW)
    public List<VersionView> history(@PathVariable UUID legalEntityId) {
        return legalEntities.history(legalEntityId);
    }

    /** Minimum-wage region in effect on {@code asOf} (BR-CORE-006), resolved through the Legal Parameter Service. */
    @GetMapping("/{legalEntityId}/min-wage-region")
    @RequiresPermission(CorePermissions.LEGAL_ENTITY_VIEW)
    public Resolution minWageRegion(@PathVariable UUID legalEntityId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return regions.legalEntityRegion(legalEntityId, asOf);
    }

    @PostMapping
    @RequiresPermission(value = CorePermissions.LEGAL_ENTITY_CREATE, tenantWide = true)
    public ResponseEntity<LegalEntityView> create(@Valid @RequestBody CreateLegalEntityRequest req) {
        LegalEntityView created = legalEntities.create(req.code(), req.status(), req.validFrom(),
                req.attributes().toAttributes());
        return ResponseEntity.created(URI.create("/api/v1/core/legal-entities/" + created.id())).body(created);
    }

    /** Effective-dated change: the attributes apply from {@code effectiveFrom} (BR-CORE-004). */
    @PostMapping("/{legalEntityId}/versions")
    @RequiresPermission(CorePermissions.LEGAL_ENTITY_UPDATE)
    public LegalEntityView changeFrom(@PathVariable UUID legalEntityId, @Valid @RequestBody ChangeLegalEntityRequest req) {
        return legalEntities.changeFrom(legalEntityId, req.effectiveFrom(), req.attributes().toAttributes(),
                req.version());
    }

    @PutMapping("/{legalEntityId}/status")
    @RequiresPermission(CorePermissions.LEGAL_ENTITY_UPDATE)
    public LegalEntityView changeStatus(@PathVariable UUID legalEntityId, @Valid @RequestBody ChangeStatusRequest req) {
        return legalEntities.changeStatus(legalEntityId, req.status(), req.version());
    }
}
