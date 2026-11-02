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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.CostCentreService;
import vn.yourhrm.core.internal.application.CostCentreService.CostCentreView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** Cost centres (BR-CORE-025, US-CORE-022). Scoped by legal entity. */
@RestController
@RequestMapping("/api/v1/core/cost-centres")
class CostCentreController {

    private final CostCentreService costCentres;

    CostCentreController(CostCentreService costCentres) {
        this.costCentres = costCentres;
    }

    record CreateCostCentreRequest(
            @NotNull UUID legalEntityId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,32}$") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 64) String glSegment,
            UUID ownerUserId,
            LocalDate validFrom,
            LocalDate validTo) {
    }

    record UpdateCostCentreRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 64) String glSegment,
            UUID ownerUserId,
            LocalDate validTo,
            @NotNull Integer version) {
    }

    @GetMapping
    @RequiresPermission(CorePermissions.COST_CENTRE_VIEW)
    public List<CostCentreView> list(
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "allDates", defaultValue = "false") boolean allDates) {
        return costCentres.list(asOf, legalEntityId, allDates);
    }

    @GetMapping("/{costCentreId}")
    @RequiresPermission(CorePermissions.COST_CENTRE_VIEW)
    public CostCentreView get(@PathVariable UUID costCentreId) {
        return costCentres.get(costCentreId);
    }

    @PostMapping
    @RequiresPermission(CorePermissions.COST_CENTRE_CREATE)
    public ResponseEntity<CostCentreView> create(@Valid @RequestBody CreateCostCentreRequest req) {
        CostCentreView created = costCentres.create(req.legalEntityId(), req.code(), req.name(), req.glSegment(),
                req.ownerUserId(), req.validFrom(), req.validTo());
        return ResponseEntity.created(URI.create("/api/v1/core/cost-centres/" + created.id())).body(created);
    }

    /** Updates name / GL segment / owner; setting {@code validTo} ends the cost centre. */
    @PutMapping("/{costCentreId}")
    @RequiresPermission(CorePermissions.COST_CENTRE_UPDATE)
    public CostCentreView update(@PathVariable UUID costCentreId, @Valid @RequestBody UpdateCostCentreRequest req) {
        return costCentres.update(costCentreId, req.name(), req.glSegment(), req.ownerUserId(), req.validTo(),
                req.version());
    }
}
