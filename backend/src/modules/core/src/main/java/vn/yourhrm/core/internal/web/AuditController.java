package vn.yourhrm.core.internal.web;

import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.AuditQueryService;
import vn.yourhrm.core.internal.application.AuditQueryService.ChainStatus;
import vn.yourhrm.core.internal.application.AuditQueryService.Filter;
import vn.yourhrm.core.internal.application.AuditQueryService.Page;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Audit search and chain verification (BR-CORE-092/093, US-CORE-092/093). The audit covers the whole tenant, so the
 * grant must be TENANT-scoped. Exporting results belongs to the SaaS report / export framework (BR-CORE-113 removed).
 */
@RestController
@RequestMapping("/api/v1/core/audit")
class AuditController {

    private final AuditQueryService audit;

    AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    /**
     * Filters: date range (instants), user, module (owner schema), entity type, record id, action, legal entity,
     * channel, client address, correlation id; {@code security=true} returns only BR-CORE-091 security events.
     */
    @GetMapping("/events")
    @RequiresPermission(value = CorePermissions.AUDIT_VIEW, tenantWide = true)
    public Page search(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "userId", required = false) UUID userId,
            @RequestParam(name = "module", required = false) String module,
            @RequestParam(name = "objectType", required = false) String objectType,
            @RequestParam(name = "objectId", required = false) UUID objectId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "channel", required = false) String channel,
            @RequestParam(name = "clientIp", required = false) String clientIp,
            @RequestParam(name = "correlationId", required = false) String correlationId,
            @RequestParam(name = "security", defaultValue = "false") boolean security,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return audit.search(new Filter(from, to, userId, module, objectType, objectId, action, legalEntityId, channel,
                clientIp, correlationId, security), page, size);
    }

    /** Recomputes the tenant's hash chain (gaps, edits, reordering) and checks the daily anchors. */
    @PostMapping("/chain/verify")
    @RequiresPermission(value = CorePermissions.AUDIT_VIEW, tenantWide = true)
    public ChainStatus verify(@RequestParam(name = "fromSeq", required = false) Long fromSeq,
            @RequestParam(name = "toSeq", required = false) Long toSeq) {
        return audit.verify(fromSeq, toSeq);
    }
}
