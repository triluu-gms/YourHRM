package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import vn.yourhrm.core.internal.application.OrgUnitService;
import vn.yourhrm.core.internal.application.OrgUnitService.Attributes;
import vn.yourhrm.core.internal.application.OrgUnitService.OrgUnitView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * The effective-dated org tree (BR-CORE-010..012, US-CORE-011..013). Reads are scoped (ORG_UNIT subtree, LEGAL_ENTITY
 * or LOCATION grants); creating needs {@code core.org_unit.create} on the parent unit (or the legal entity for a
 * root), changing needs {@code core.org_unit.update} on the unit.
 */
@RestController
@RequestMapping("/api/v1/core/org-units")
class OrgUnitController {

    private final OrgUnitService orgUnits;

    OrgUnitController(OrgUnitService orgUnits) {
        this.orgUnits = orgUnits;
    }

    record AttributesRequest(
            @NotBlank @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn,
            @NotNull UUID typeId,
            UUID parentId,
            @NotNull UUID legalEntityId,
            UUID locationId,
            UUID costCentreId,
            UUID managerUserId,
            Integer sortOrder) {

        Attributes toAttributes() {
            return new Attributes(nameVi, nameEn, typeId, parentId, legalEntityId, locationId, costCentreId,
                    managerUserId, sortOrder);
        }
    }

    record CreateOrgUnitRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,32}$") String code,
            LocalDate validFrom,
            @NotNull @Valid AttributesRequest attributes) {
    }

    record ChangeOrgUnitRequest(@NotNull LocalDate effectiveFrom, @NotNull @Valid AttributesRequest attributes) {
    }

    record CloseOrgUnitRequest(@NotNull LocalDate effectiveFrom) {
    }

    /** Org tree as of a date (flat, path order; build the tree from parentId). */
    @GetMapping
    @RequiresPermission(CorePermissions.ORG_UNIT_VIEW)
    public List<OrgUnitView> tree(
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "root", required = false) UUID root,
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed,
            @RequestParam(name = "q", required = false) String q) {
        return orgUnits.tree(asOf, root, includeClosed, q);
    }

    @GetMapping("/{orgUnitId}")
    @RequiresPermission(CorePermissions.ORG_UNIT_VIEW)
    public OrgUnitView get(@PathVariable UUID orgUnitId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return orgUnits.get(orgUnitId, asOf);
    }

    @GetMapping("/{orgUnitId}/versions")
    @RequiresPermission(CorePermissions.ORG_UNIT_VIEW)
    public List<OrgUnitView> history(@PathVariable UUID orgUnitId) {
        return orgUnits.history(orgUnitId);
    }

    @GetMapping("/{orgUnitId}/ancestors")
    @RequiresPermission(CorePermissions.ORG_UNIT_VIEW)
    public List<OrgUnitView> ancestors(@PathVariable UUID orgUnitId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return orgUnits.ancestors(orgUnitId, asOf);
    }

    @GetMapping("/{orgUnitId}/descendants")
    @RequiresPermission(CorePermissions.ORG_UNIT_VIEW)
    public List<OrgUnitView> descendants(@PathVariable UUID orgUnitId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return orgUnits.descendants(orgUnitId, asOf);
    }

    @PostMapping
    @RequiresPermission(CorePermissions.ORG_UNIT_CREATE)
    public ResponseEntity<OrgUnitView> create(@Valid @RequestBody CreateOrgUnitRequest req) {
        OrgUnitView created = orgUnits.create(req.code(), req.validFrom(), req.attributes().toAttributes());
        return ResponseEntity.created(URI.create("/api/v1/core/org-units/" + created.id())).body(created);
    }

    /** Effective-dated change (rename, move, manager, cost centre, location...) from {@code effectiveFrom}. */
    @PostMapping("/{orgUnitId}/versions")
    @RequiresPermission(CorePermissions.ORG_UNIT_UPDATE)
    public OrgUnitView changeFrom(@PathVariable UUID orgUnitId, @Valid @RequestBody ChangeOrgUnitRequest req) {
        return orgUnits.changeFrom(orgUnitId, req.effectiveFrom(), req.attributes().toAttributes());
    }

    @PostMapping("/{orgUnitId}/close")
    @RequiresPermission(CorePermissions.ORG_UNIT_UPDATE)
    public OrgUnitView close(@PathVariable UUID orgUnitId, @Valid @RequestBody CloseOrgUnitRequest req) {
        return orgUnits.close(orgUnitId, req.effectiveFrom());
    }
}
