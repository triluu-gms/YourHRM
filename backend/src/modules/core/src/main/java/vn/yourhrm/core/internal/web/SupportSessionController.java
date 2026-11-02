package vn.yourhrm.core.internal.web;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.SupportSessionService;
import vn.yourhrm.core.internal.application.SupportSessionService.SupportEventView;
import vn.yourhrm.core.internal.application.SupportSessionService.SupportSessionView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** Platform support sessions in this tenant and what was done in them (BR-CORE-041, US-CORE-042). */
@RestController
@RequestMapping("/api/v1/core/support-sessions")
class SupportSessionController {

    private final SupportSessionService sessions;

    SupportSessionController(SupportSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    @RequiresPermission(value = CorePermissions.SUPPORT_SESSION_VIEW, tenantWide = true)
    public List<SupportSessionView> list() {
        return sessions.sessions();
    }

    @GetMapping("/{supportGrantId}/events")
    @RequiresPermission(value = CorePermissions.SUPPORT_SESSION_VIEW, tenantWide = true)
    public List<SupportEventView> events(@PathVariable UUID supportGrantId) {
        return sessions.events(supportGrantId);
    }
}
