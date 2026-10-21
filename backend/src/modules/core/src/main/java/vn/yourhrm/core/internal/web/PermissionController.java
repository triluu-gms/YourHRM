package vn.yourhrm.core.internal.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.PermissionQueryService;
import vn.yourhrm.core.internal.application.PermissionQueryService.PermissionView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** Global permission catalogue (US-CORE-033). */
@RestController
@RequestMapping("/api/v1/core/permissions")
class PermissionController {

    private final PermissionQueryService permissions;

    PermissionController(PermissionQueryService permissions) {
        this.permissions = permissions;
    }

    @GetMapping
    @RequiresPermission(CorePermissions.PERMISSION_VIEW)
    public List<PermissionView> list(@RequestParam(name = "module", required = false) String module,
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return permissions.list(module, includeInactive);
    }
}
