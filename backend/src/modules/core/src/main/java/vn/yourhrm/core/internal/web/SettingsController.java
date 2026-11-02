package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.Settings.Scope;
import vn.yourhrm.core.internal.application.SettingsService;
import vn.yourhrm.core.internal.application.SettingsService.Resolved;
import vn.yourhrm.core.internal.application.SettingsService.SettingView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** Tenant settings administration (BR-CORE-084, US-CORE-085). Tenant administration: TENANT-scoped grants. */
@RestController
@RequestMapping("/api/v1/core/settings")
class SettingsController {

    private final SettingsService settings;

    SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    /** {@code value} is any JSON value matching the setting's type. */
    record SetValueRequest(@NotBlank String level, UUID legalEntityId, UUID locationId, @NotNull Object value,
            LocalDate validFrom) {
    }

    @GetMapping
    @RequiresPermission(value = CorePermissions.SETTING_VIEW, tenantWide = true)
    public List<SettingView> list() {
        return settings.list();
    }

    @GetMapping("/{key}")
    @RequiresPermission(value = CorePermissions.SETTING_VIEW, tenantWide = true)
    public SettingView get(@PathVariable String key) {
        return settings.get(key);
    }

    /** The value that applies at a scope on a date, and where it comes from. */
    @GetMapping("/{key}/resolve")
    @RequiresPermission(value = CorePermissions.SETTING_VIEW, tenantWide = true)
    public Resolved resolve(@PathVariable String key,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "locationId", required = false) UUID locationId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return settings.resolve(key, new Scope(legalEntityId, locationId), asOf);
    }

    @PutMapping("/{key}")
    @RequiresPermission(value = CorePermissions.SETTING_UPDATE, tenantWide = true)
    public Resolved set(@PathVariable String key, @Valid @RequestBody SetValueRequest req) {
        return settings.set(key, req.level(), req.legalEntityId(), req.locationId(), req.value(), req.validFrom());
    }

    @DeleteMapping("/{key}/values/{valueId}")
    @RequiresPermission(value = CorePermissions.SETTING_UPDATE, tenantWide = true)
    public ResponseEntity<Void> remove(@PathVariable String key, @PathVariable UUID valueId) {
        settings.remove(key, valueId);
        return ResponseEntity.noContent().build();
    }
}
