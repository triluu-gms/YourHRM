package vn.yourhrm.core.internal.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * The user's in-app inbox (US-CORE-072) and the delivery monitor (US-CORE-073). Read items stay visible for
 * {@value #READ_RETENTION_DAYS} days; partitions older than 13 months are dropped by the retention job.
 */
@Service
public class NotificationInboxService {

    static final int READ_RETENTION_DAYS = 180;

    private final JdbcClient jdbc;
    private final NotificationTemplateRegistry templates;
    private final NotificationService notifications;

    public NotificationInboxService(JdbcClient jdbc, NotificationTemplateRegistry templates,
            NotificationService notifications) {
        this.jdbc = jdbc;
        this.templates = templates;
        this.notifications = notifications;
    }

    public record InboxItem(UUID id, Instant createdAt, String eventCode, String category, String title, String body,
            String deepLink, Instant readAt) {
    }

    public record Inbox(List<InboxItem> items, long unread, int page, int size) {
    }

    public record DeliveryItem(UUID deliveryId, UUID notificationId, String eventCode, UUID recipientUserId,
            String channel, String destinationMasked, String status, int attempts, String lastError,
            boolean hasAttachment, Instant createdAt, Instant sentAt, Instant deliveredAt, Instant failedAt) {
    }

    public record TemplateItem(String eventCode, String category, boolean mandatory, String channel, String locale,
            String subject, String body, String bodyFormat, List<NotificationTemplateDefinition.Placeholder> placeholders,
            boolean attachmentAllowed) {
    }

    public record Preview(String subject, String body, String bodyFormat) {
    }

    // ------------------------------------------------------------------------------------ inbox

    @Transactional(readOnly = true)
    public Inbox inbox(boolean unreadOnly, int page, int size) {
        UUID tenant = tenant();
        UUID user = user();
        List<InboxItem> items = jdbc.sql("""
                        select id, created_at, event_code, category, title, body, deep_link, read_at
                          from core.notification
                         where tenant_id = :t and recipient_user_id = :u and archived_at is null
                           and (read_at is null or (not :unread and read_at > now() - make_interval(days => :keep)))
                         order by created_at desc limit :limit offset :offset""")
                .param("t", tenant).param("u", user).param("unread", unreadOnly).param("keep", READ_RETENTION_DAYS)
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new InboxItem(rs.getObject(1, UUID.class), instant(rs, 2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), instant(rs, 8)))
                .list();
        return new Inbox(items, unreadCount(), page, size);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return jdbc.sql("""
                        select count(*) from core.notification
                         where tenant_id = :t and recipient_user_id = :u and read_at is null and archived_at is null""")
                .param("t", tenant()).param("u", user()).query(Long.class).single();
    }

    @Transactional
    public void markRead(UUID notificationId) {
        int updated = jdbc.sql("""
                        update core.notification set read_at = coalesce(read_at, now())
                         where tenant_id = :t and id = :id and recipient_user_id = :u""")
                .param("t", tenant()).param("id", notificationId).param("u", user()).update();
        if (updated == 0) {
            throw ApiException.notFound("Notification", notificationId);
        }
    }

    @Transactional
    public int markAllRead() {
        return jdbc.sql("""
                        update core.notification set read_at = now()
                         where tenant_id = :t and recipient_user_id = :u and read_at is null""")
                .param("t", tenant()).param("u", user()).update();
    }

    // ------------------------------------------------------------------------------------ delivery monitor

    @Transactional(readOnly = true)
    public List<DeliveryItem> deliveries(String status, String channel, int page, int size) {
        return jdbc.sql("""
                        select d.id, d.notification_id, n.event_code, n.recipient_user_id, d.channel, d.destination_masked,
                               d.status, d.attempts, d.last_error, d.has_attachment, d.created_at, d.sent_at,
                               d.delivered_at, d.failed_at
                          from core.notification_delivery d
                          join core.notification n on n.tenant_id = d.tenant_id and n.id = d.notification_id
                                                  and n.created_at = d.notification_created_at
                         where d.tenant_id = :t and (cast(:s as text) is null or d.status = :s)
                           and (cast(:c as text) is null or d.channel = :c)
                         order by d.created_at desc limit :limit offset :offset""")
                .param("t", tenant()).param("s", status).param("c", channel).param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, n) -> new DeliveryItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getInt(8), rs.getString(9), rs.getBoolean(10), instant(rs, 11), instant(rs, 12),
                        instant(rs, 13), instant(rs, 14)))
                .list();
    }

    // ------------------------------------------------------------------------------------ templates

    public List<TemplateItem> templates() {
        return templates.all().stream().map(t -> new TemplateItem(t.eventCode(), t.category(), t.mandatory(),
                t.channel(), t.locale(), t.subject(), t.body(), t.bodyFormat(), t.placeholders(),
                t.attachmentAllowed())).toList();
    }

    /** Renders a template with sample values (US-CORE-070 AC: support previews with sample data). */
    @Transactional(readOnly = true)
    public Preview preview(String eventCode, String channel, String locale, Map<String, Object> sample,
            String deepLink) {
        return notifications.render(eventCode, channel, locale, sample, deepLink, "EMAIL".equals(channel))
                .map(r -> new Preview(r.subject(), r.body(), r.bodyFormat()))
                .orElseThrow(() -> ApiException.notFound("Template", eventCode + "/" + channel));
    }

    // ------------------------------------------------------------------------------------ helpers

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID user() {
        UUID user = TenantContext.current().userAccountId();
        if (user == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "no-user-account", "The inbox needs a tenant user account");
        }
        return user;
    }
}
