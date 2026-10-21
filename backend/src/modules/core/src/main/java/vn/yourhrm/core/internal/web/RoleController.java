package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.RoleService;
import vn.yourhrm.core.internal.application.RoleService.Grant;
import vn.yourhrm.core.internal.application.RoleService.RoleDetail;
import vn.yourhrm.core.internal.application.RoleService.RoleSummary;
import vn.yourhrm.core.internal.domain.FieldMaskLevel;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** Roles and permission matrix (US-CORE-034). Tenant administration: TENANT-scoped grants required. */
@RestController
@RequestMapping("/api/v1/core/roles")
class RoleController {

    private final RoleService roles;

    RoleController(RoleService roles) {
        this.roles = roles;
    }

    record PermissionGrant(@NotBlank String code, FieldMaskLevel fieldMaskLevel) {
        Grant toGrant() {
            return new Grant(code, fieldMaskLevel);
        }
    }

    record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{2,64}$") String code,
            @NotBlank @Size(max = 200) String nameVi,
            @NotBlank @Size(max = 200) String nameEn,
            List<@Valid PermissionGrant> permissions) {
    }

    record UpdateRoleRequest(@NotBlank @Size(max = 200) String nameVi, @NotBlank @Size(max = 200) String nameEn,
            @NotNull Integer version) {
    }

    record CopyRoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{2,64}$") String code,
            @NotBlank @Size(max = 200) String nameVi,
            @NotBlank @Size(max = 200) String nameEn) {
    }

    record ReplacePermissionsRequest(@NotNull List<@Valid PermissionGrant> permissions, Integer version) {
    }

    @GetMapping
    @RequiresPermission(value = CorePermissions.ROLE_VIEW, tenantWide = true)
    public List<RoleSummary> list(@RequestParam(name = "q", required = false) String q) {
        return roles.list(q);
    }

    @GetMapping("/{roleId}")
    @RequiresPermission(value = CorePermissions.ROLE_VIEW, tenantWide = true)
    public RoleDetail get(@PathVariable UUID roleId) {
        return roles.get(roleId);
    }

    @PostMapping
    @RequiresPermission(value = CorePermissions.ROLE_CREATE, tenantWide = true)
    public ResponseEntity<RoleDetail> create(@Valid @RequestBody CreateRoleRequest req) {
        List<Grant> grants = req.permissions() == null ? List.of()
                : req.permissions().stream().map(PermissionGrant::toGrant).toList();
        RoleDetail created = roles.create(req.code(), req.nameVi(), req.nameEn(), grants);
        return ResponseEntity.created(URI.create("/api/v1/core/roles/" + created.id())).body(created);
    }

    @PutMapping("/{roleId}")
    @RequiresPermission(value = CorePermissions.ROLE_UPDATE, tenantWide = true)
    public RoleDetail update(@PathVariable UUID roleId, @Valid @RequestBody UpdateRoleRequest req) {
        return roles.update(roleId, req.nameVi(), req.nameEn(), req.version());
    }

    @DeleteMapping("/{roleId}")
    @RequiresPermission(value = CorePermissions.ROLE_DELETE, tenantWide = true)
    public ResponseEntity<Void> delete(@PathVariable UUID roleId) {
        roles.delete(roleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{roleId}/copy")
    @RequiresPermission(value = CorePermissions.ROLE_CREATE, tenantWide = true)
    public ResponseEntity<RoleDetail> copy(@PathVariable UUID roleId, @Valid @RequestBody CopyRoleRequest req) {
        RoleDetail created = roles.copy(roleId, req.code(), req.nameVi(), req.nameEn());
        return ResponseEntity.created(URI.create("/api/v1/core/roles/" + created.id())).body(created);
    }

    @PutMapping("/{roleId}/permissions")
    @RequiresPermission(value = CorePermissions.ROLE_UPDATE, tenantWide = true)
    public RoleDetail replacePermissions(@PathVariable UUID roleId, @Valid @RequestBody ReplacePermissionsRequest req) {
        return roles.replacePermissions(roleId,
                req.permissions().stream().map(PermissionGrant::toGrant).toList(), req.version());
    }
}
