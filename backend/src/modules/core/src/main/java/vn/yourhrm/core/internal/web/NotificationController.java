package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.NotificationInboxService;
import vn.yourhrm.core.internal.application.NotificationInboxService.DeliveryItem;
import vn.yourhrm.core.internal.application.NotificationInboxService.Inbox;
import vn.yourhrm.core.internal.application.NotificationInboxService.Preview;
import vn.yourhrm.core.internal.application.NotificationInboxService.TemplateItem;
import vn.yourhrm.core.internal.application.NotificationService;
import vn.yourhrm.core.internal.application.NotificationStreams;
import vn.yourhrm.shared.auth.api.PublicEndpoint;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Notification centre (BR-CORE-070, US-CORE-072/073/070): the caller's in-app inbox and live stream, the delivery
 * monitor with resend, and the template registry with preview.
 */
@RestController
@RequestMapping("/api/v1/core")
class NotificationController {

    private final NotificationInboxService inbox;
    private final NotificationService notifications;
    private final NotificationStreams streams;

    NotificationController(NotificationInboxService inbox, NotificationService notifications,
            NotificationStreams streams) {
        this.inbox = inbox;
        this.notifications = notifications;
        this.streams = streams;
    }

    record PreviewRequest(@NotBlank String eventCode, @NotBlank String channel, String locale,
            Map<String, Object> sample, String deepLink) {
    }

    // ------------------------------------------------------------------------------------ my inbox

    @GetMapping("/notifications")
    @PublicEndpoint(reason = "Every user reads their own inbox")
    public Inbox list(@RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return inbox.inbox(unreadOnly, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/notifications/unread-count")
    @PublicEndpoint(reason = "Header counter of the caller's own inbox")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", inbox.unreadCount());
    }

    @PostMapping("/notifications/{notificationId}/read")
    @PublicEndpoint(reason = "A user marks their own notifications; the service checks the recipient")
    public ResponseEntity<Void> markRead(@PathVariable UUID notificationId) {
        inbox.markRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    @PublicEndpoint(reason = "A user marks their own notifications")
    public Map<String, Integer> markAllRead() {
        return Map.of("marked", inbox.markAllRead());
    }

    /** Live stream of new inbox items (server-sent events). */
    @GetMapping(path = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PublicEndpoint(reason = "A user subscribes to their own inbox")
    public SseEmitter stream() {
        TenantContext ctx = TenantContext.current();
        if (ctx.userAccountId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "no-user-account",
                    "The inbox needs a tenant user account");
        }
        return streams.open(ctx.tenantId(), ctx.userAccountId());
    }

    // ------------------------------------------------------------------------------------ delivery monitor

    @GetMapping("/notification-deliveries")
    @RequiresPermission(value = CorePermissions.NOTIFICATION_ADMIN, tenantWide = true)
    public List<DeliveryItem> deliveries(@RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "channel", required = false) String channel,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        return inbox.deliveries(status, channel, Math.max(page, 0), Math.min(Math.max(size, 1), 200));
    }

    @PostMapping("/notification-deliveries/{deliveryId}/resend")
    @RequiresPermission(value = CorePermissions.NOTIFICATION_ADMIN, tenantWide = true)
    public ResponseEntity<Void> resend(@PathVariable UUID deliveryId) {
        notifications.resend(deliveryId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------------------ templates

    @GetMapping("/notification-templates")
    @RequiresPermission(value = CorePermissions.NOTIFICATION_TEMPLATE_VIEW, tenantWide = true)
    public List<TemplateItem> templates() {
        return inbox.templates();
    }

    @PostMapping("/notification-templates/preview")
    @RequiresPermission(value = CorePermissions.NOTIFICATION_TEMPLATE_VIEW, tenantWide = true)
    public Preview preview(@Valid @RequestBody PreviewRequest req) {
        return inbox.preview(req.eventCode(), req.channel(), req.locale() == null ? "vi-VN" : req.locale(),
                req.sample() == null ? Map.of() : req.sample(), req.deepLink());
    }
}
