package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
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
import vn.yourhrm.core.internal.application.OrgUnitTypeService;
import vn.yourhrm.core.internal.application.OrgUnitTypeService.OrgUnitTypeView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** Org unit types (BR-CORE-010, US-CORE-010). Readable by anyone who can see org units; managed tenant-wide. */
@RestController
@RequestMapping("/api/v1/core/org-unit-types")
class OrgUnitTypeController {

    private final OrgUnitTypeService types;

    OrgUnitTypeController(OrgUnitTypeService types) {
        this.types = types;
    }

    record CreateTypeRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String code,
            @NotBlank @Size(max = 100) String nameVi,
            @NotBlank @Size(max = 100) String nameEn,
            @Min(1) Integer levelHint,
            Integer sortOrder) {
    }

    record UpdateTypeRequest(
            @NotBlank @Size(max = 100) String nameVi,
            @NotBlank @Size(max = 100) String nameEn,
            @Min(1) Integer levelHint,
            @NotNull Integer sortOrder,
            @NotNull Boolean active) {
    }

    @GetMapping
    @RequiresPermission(CorePermissions.ORG_UNIT_VIEW)
    public List<OrgUnitTypeView> list(@RequestParam(name = "includeInactive", defaultValue = "false") boolean all) {
        return types.list(all);
    }

    @PostMapping
    @RequiresPermission(value = CorePermissions.ORG_UNIT_TYPE_UPDATE, tenantWide = true)
    public ResponseEntity<OrgUnitTypeView> create(@Valid @RequestBody CreateTypeRequest req) {
        OrgUnitTypeView created = types.create(req.code(), req.nameVi(), req.nameEn(), req.levelHint(), req.sortOrder());
        return ResponseEntity.created(URI.create("/api/v1/core/org-unit-types/" + created.id())).body(created);
    }

    @PutMapping("/{typeId}")
    @RequiresPermission(value = CorePermissions.ORG_UNIT_TYPE_UPDATE, tenantWide = true)
    public OrgUnitTypeView update(@PathVariable UUID typeId, @Valid @RequestBody UpdateTypeRequest req) {
        return types.update(typeId, req.nameVi(), req.nameEn(), req.levelHint(), req.sortOrder(), req.active());
    }
}
