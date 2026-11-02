package vn.yourhrm.core.internal.application;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;
import vn.yourhrm.core.api.NotificationDeliveryStatus;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.internal.infra.AuditWriter;
import vn.yourhrm.shared.common.delivery.EmailDeliveryAdapter;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The notification centre (BR-CORE-070/074, US-CORE-071/073/074). P1 channels: IN_APP (Core's inbox, delivered on
 * insert) and EMAIL (handed to the SaaS delivery adapter after commit; the adapter reports the status back).
 *
 * <p>BR-CORE-074 is enforced in three places: templates cannot carry sensitive or money placeholders
 * ({@link NotificationTemplateRegistry}), values are inserted as text (HTML-escaped in HTML bodies), and an attachment
 * is accepted only for an event registered as the payslip exception, only as a PDF the sender declares encrypted with
 * the recipient's own password, and only for a single recipient; every send of an attachment is audited with the
 * file hash.
 */
@Service
public class NotificationService implements Notifications, NotificationDeliveryStatus {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Set<String> MODULES = Set.of("saas", "core", "hr", "att", "pay", "rec");
    private static final Set<String> REPORTED = Set.of("SENT", "DELIVERED", "OPENED", "FAILED", "BOUNCED");

    private final JdbcClient jdbc;
    private final NotificationTemplateRegistry templates;
    private final CoreAuthorizationService authz;
    private final ObjectProvider<EmailDeliveryAdapter> emailAdapter;
    private final NotificationStreams streams;
    private final AuditWriter audit;
    private final BusinessClock clock;
    private final TransactionTemplate afterCommitTx;
    private final String baseUrl;

    public NotificationService(JdbcClient jdbc, NotificationTemplateRegistry templates, CoreAuthorizationService authz,
            ObjectProvider<EmailDeliveryAdapter> emailAdapter, NotificationStreams streams, AuditWriter audit,
            BusinessClock clock, PlatformTransactionManager transactionManager,
            @Value("${yhrm.app.base-url:http://localhost:5173}") String baseUrl) {
        this.jdbc = jdbc;
        this.templates = templates;
        this.authz = authz;
        this.emailAdapter = emailAdapter;
        this.streams = streams;
        this.audit = audit;
        this.clock = clock;
        this.afterCommitTx = new TransactionTemplate(transactionManager);
        this.afterCommitTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** A recipient as needed for rendering and delivery. */
    private record Recipient(UUID id, String locale, String email) {
    }

    /** A rendered text. */
    record Rendered(UUID templateId, Integer templateVersion, String category, String subject, String body,
            String bodyFormat) {
    }

    // ================================================================================================ send

    @Override
    @Transactional
    public SendResult send(SendRequest r) {
        UUID tenant = TenantContext.current().tenantId();
        if (!MODULES.contains(r.sourceModule())) {
            throw ApiException.unprocessable("unknown-module", "Unknown source module " + r.sourceModule());
        }
        if (!templates.isRegistered(r.eventCode())) {
            throw ApiException.unprocessable("unknown-notification-event",
                    "No notification templates are registered for " + r.eventCode());
        }
        List<Recipient> recipients = recipients(tenant, r.recipients());
        checkAttachment(tenant, r, recipients);

        List<UUID> ids = new ArrayList<>();
        Set<UUID> deduplicated = new LinkedHashSet<>();
        for (Recipient to : recipients) {
            if (r.dedupeKey() != null && isDuplicate(tenant, to.id(), r.dedupeKey())) {
                deduplicated.add(to.id());
                continue;
            }
            ids.add(deliver(tenant, r, to));
        }
        return new SendResult(ids, deduplicated);
    }

    private UUID deliver(UUID tenant, SendRequest r, Recipient to) {
        Rendered inApp = render(r.eventCode(), "IN_APP", to.locale(), r.payload(), r.deepLink(), false)
                .orElseThrow(() -> new IllegalStateException("No IN_APP template for " + r.eventCode()));
        UUID id = Ids.newId();
        Timestamp created = Timestamp.from(clock.now());
        jdbc.sql("""
                        insert into core.notification (tenant_id, id, created_at, recipient_user_id, event_code, category,
                               source_module, source_ref_type, source_ref_id, template_id, template_version, title, body,
                               deep_link, dedupe_key, correlation_id)
                        values (:t, :id, :at, :to, :e, :cat, :m, :refType, :refId, :tpl, :tplv, :title, :body, :link,
                                :dedupe, :corr)""")
                .param("t", tenant).param("id", id).param("at", created).param("to", to.id())
                .param("e", r.eventCode()).param("cat", inApp.category()).param("m", r.sourceModule())
                .param("refType", r.sourceRefType()).param("refId", r.sourceRefId()).param("tpl", inApp.templateId())
                .param("tplv", inApp.templateVersion()).param("title", inApp.subject()).param("body", inApp.body())
                .param("link", r.deepLink()).param("dedupe", r.dedupeKey())
                .param("corr", TenantContext.current().requestId())
                .update();
        insertDelivery(tenant, id, created, "IN_APP", null, "DELIVERED", false, null);

        Optional<Rendered> email = render(r.eventCode(), "EMAIL", to.locale(), r.payload(), r.deepLink(), true);
        if (email.isPresent()) {
            boolean withAttachment = r.attachment() != null;
            if (to.email() == null || to.email().isBlank()) {
                insertDelivery(tenant, id, created, "EMAIL", null, "SUPPRESSED", false, "No e-mail address");
            } else {
                UUID deliveryId = insertDelivery(tenant, id, created, "EMAIL", mask(to.email()), "QUEUED",
                        withAttachment, null);
                if (withAttachment) {
                    audit.append(new AuditWriter.Event(tenant, "USER", TenantContext.current().userAccountId(), null,
                            null, "NOTIFICATION_ATTACHMENT_SENT", "notification", id, null, Map.of(
                                    "recipient", to.id().toString(), "documentId",
                                    r.attachment().documentId().toString(), "sha256", r.attachment().sha256(),
                                    "eventCode", r.eventCode()), false));
                }
                Rendered mail = email.get();
                EmailDeliveryAdapter.Attachment file = withAttachment ? new EmailDeliveryAdapter.Attachment(
                        r.attachment().documentId(), r.attachment().fileName(), r.attachment().mediaType(),
                        r.attachment().sha256()) : null;
                afterCommit(() -> dispatchEmail(tenant, deliveryId, to.email(), mail.subject(), mail.body(),
                        mail.bodyFormat(), file));
            }
        }
        UUID recipient = to.id();
        afterCommit(() -> streams.push(tenant, recipient, id, inApp.subject()));
        return id;
    }

    /** Hands one e-mail to the SaaS adapter (after commit, so a rolled-back change never sends anything). */
    private void dispatchEmail(UUID tenant, UUID deliveryId, String to, String subject, String body, String format,
            EmailDeliveryAdapter.Attachment attachment) {
        EmailDeliveryAdapter adapter = emailAdapter.getIfAvailable();
        if (adapter == null) {
            log.debug("No e-mail delivery adapter; delivery {} stays QUEUED", deliveryId);
            return;
        }
        try {
            UUID job = adapter.enqueue(tenant, deliveryId, to, subject, body, format, attachment);
            afterCommitTx.executeWithoutResult(s -> jdbc.sql("""
                            update core.notification_delivery set adapter_job_id = :j, attempts = attempts + 1,
                                   updated_at = now() where tenant_id = :t and id = :id""")
                    .param("j", job).param("t", tenant).param("id", deliveryId).update());
        } catch (RuntimeException e) {
            log.warn("E-mail delivery {} could not be queued", deliveryId, e);
            afterCommitTx.executeWithoutResult(s -> jdbc.sql("""
                            update core.notification_delivery set status = 'FAILED', failed_at = now(), last_error = :e,
                                   attempts = attempts + 1, updated_at = now() where tenant_id = :t and id = :id""")
                    .param("e", String.valueOf(e.getMessage())).param("t", tenant).param("id", deliveryId).update());
        }
    }

    // ================================================================================================ status & resend

    @Override
    @Transactional
    public void report(UUID deliveryId, String status, String providerMessageId, String error) {
        if (!REPORTED.contains(status)) {
            throw ApiException.unprocessable("invalid-delivery-status", "Unknown delivery status " + status);
        }
        int updated = jdbc.sql("""
                        update core.notification_delivery
                           set status = :s, provider_message_id = coalesce(:p, provider_message_id),
                               last_error = case when :s in ('FAILED', 'BOUNCED') then :e else last_error end,
                               sent_at = case when :s = 'SENT' then now() else sent_at end,
                               delivered_at = case when :s = 'DELIVERED' then now() else delivered_at end,
                               opened_at = case when :s = 'OPENED' then now() else opened_at end,
                               failed_at = case when :s in ('FAILED', 'BOUNCED') then now() else failed_at end,
                               updated_at = now()
                         where tenant_id = :t and id = :id""")
                .param("s", status).param("p", providerMessageId).param("e", error)
                .param("t", TenantContext.current().tenantId()).param("id", deliveryId).update();
        if (updated == 0) {
            throw ApiException.notFound("Notification delivery", deliveryId);
        }
    }

    /**
     * Sends a failed / bounced e-mail again (US-CORE-073), from the notification's own (already safe) text plus its
     * deep link. Deliveries that carried an attachment are resent by their source module (payroll), which audits it.
     */
    @Transactional
    public void resend(UUID deliveryId) {
        UUID tenant = TenantContext.current().tenantId();
        record Row(String channel, String status, boolean attachment, String title, String body, String link,
                String email) {
        }
        Row d = jdbc.sql("""
                        select d.channel, d.status, d.has_attachment, n.title, n.body, n.deep_link, u.email
                          from core.notification_delivery d
                          join core.notification n on n.tenant_id = d.tenant_id and n.id = d.notification_id
                                                  and n.created_at = d.notification_created_at
                          join core.user_account u on u.tenant_id = n.tenant_id and u.id = n.recipient_user_id
                         where d.tenant_id = :t and d.id = :id""")
                .param("t", tenant).param("id", deliveryId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)))
                .optional().orElseThrow(() -> ApiException.notFound("Notification delivery", deliveryId));
        if (!"EMAIL".equals(d.channel()) || !Set.of("FAILED", "BOUNCED", "QUEUED", "SUPPRESSED").contains(d.status())) {
            throw ApiException.conflict("not-resendable", "Only e-mails that failed, bounced or never left can be resent");
        }
        if (d.attachment()) {
            throw ApiException.conflict("resend-from-source",
                    "An e-mail with an attachment is resent by its source module (e.g. payroll re-publishes the payslip)");
        }
        if (d.email() == null || d.email().isBlank()) {
            throw ApiException.unprocessable("no-email-address", "The recipient has no e-mail address");
        }
        jdbc.sql("""
                        update core.notification_delivery set status = 'QUEUED', destination_masked = :dest, last_error = null,
                               failed_at = null, updated_at = now() where tenant_id = :t and id = :id""")
                .param("dest", mask(d.email())).param("t", tenant).param("id", deliveryId).update();
        String body = d.body() + (d.link() == null ? "" : "\n\n" + baseUrl + d.link());
        afterCommit(() -> dispatchEmail(tenant, deliveryId, d.email(), d.title(), body, "TEXT", null));
    }

    // ================================================================================================ rendering

    /**
     * Renders an event's template for a channel in the recipient's locale (vi-VN fallback). A row of the effective
     * template view (platform hot-fix now, tenant override in P2) wins over the code template.
     */
    Optional<Rendered> render(String eventCode, String channel, String locale, Map<String, Object> payload,
            String deepLink, boolean absoluteLink) {
        Optional<NotificationTemplateDefinition> code = templates.find(eventCode, channel, locale);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        NotificationTemplateDefinition t = code.get();
        record Db(UUID id, int version, String subject, String body, String format) {
        }
        Optional<Db> db = jdbc.sql("""
                        select id, template_version, subject, body, body_format from core.v_notification_template_effective
                         where event_code = :e and channel = :c and locale = :l""")
                .param("e", eventCode).param("c", channel).param("l", t.locale())
                .query((rs, n) -> new Db(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .optional();
        String subject = db.map(Db::subject).orElse(t.subject());
        String body = db.map(Db::body).orElse(t.body());
        String format = db.map(Db::format).orElse(t.bodyFormat());
        Map<String, Object> values = new LinkedHashMap<>(payload);
        values.put("deepLink", deepLink == null ? "" : (absoluteLink ? baseUrl + deepLink : deepLink));
        return Optional.of(new Rendered(db.map(Db::id).orElse(null), db.map(Db::version).orElse(null), t.category(),
                fill(subject, values, false), fill(body, values, "HTML".equals(format)), format));
    }

    private static String fill(String text, Map<String, Object> values, boolean html) {
        if (text == null) {
            return null;
        }
        Matcher m = NotificationTemplateRegistry.PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = values.get(m.group(1));
            if (v == null) {
                throw ApiException.unprocessable("missing-placeholder-value",
                        "No value for placeholder {{" + m.group(1) + "}}");
            }
            String s = String.valueOf(v);
            m.appendReplacement(out, Matcher.quoteReplacement(html ? HtmlUtils.htmlEscape(s) : s));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ================================================================================================ helpers

    /** Listed users plus audiences (authorisation service), existing and able to sign in, in a stable order. */
    private List<Recipient> recipients(UUID tenant, Recipients spec) {
        Set<UUID> ids = new TreeSet<>(spec.userIds());
        LocalDate today = clock.today();
        for (Audience a : spec.audiences()) {
            if (a.permission() != null) {
                ids.addAll(authz.usersWhoCan(a.permission(), a.target(), today));
            } else if (a.roleCode() != null) {
                ids.addAll(authz.usersWithRoleCovering(a.roleCode(), a.target(), today));
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select id, locale, email from core.user_account
                         where tenant_id = :t and id in (:ids) and status in ('ACTIVE', 'FORMER_EMPLOYEE')
                           and (expires_at is null or expires_at > now())
                         order by id""")
                .param("t", tenant).param("ids", List.copyOf(ids))
                .query((rs, n) -> new Recipient(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }

    private void checkAttachment(UUID tenant, SendRequest r, List<Recipient> recipients) {
        Attachment a = r.attachment();
        if (a == null) {
            return;
        }
        boolean allowed = templates.all().stream().anyMatch(t -> t.eventCode().equals(r.eventCode())
                && "EMAIL".equals(t.channel()) && t.attachmentAllowed());
        if (!allowed) {
            throw ApiException.unprocessable("attachment-not-allowed",
                    r.eventCode() + " may not carry attachments; send a deep link (BR-CORE-074)");
        }
        if (!a.encryptedWithRecipientPassword() || !"application/pdf".equals(a.mediaType())) {
            throw ApiException.unprocessable("attachment-not-encrypted",
                    "Only a PDF protected with the employee's own password may be attached (R2-17)");
        }
        if (recipients.size() != 1) {
            throw ApiException.unprocessable("attachment-single-recipient",
                    "A protected attachment is personal: send it to exactly one recipient");
        }
        if (a.sha256() == null || a.sha256().isBlank() || !jdbc.sql("""
                        select exists (select 1 from core.document where tenant_id = :t and id = :id and status = 'ACTIVE')""")
                .param("t", tenant).param("id", a.documentId()).query(Boolean.class).single()) {
            throw ApiException.unprocessable("unknown-document", "Attachment " + a.documentId() + " not found");
        }
    }

    /** BR-CORE-073 de-duplication within 24 hours, serialised per (recipient, key). */
    private boolean isDuplicate(UUID tenant, UUID recipient, String key) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(cast(:t as text) || ':' || cast(:u as text) || ':' || :k, 0))")
                .param("t", tenant).param("u", recipient).param("k", key).query((rs, n) -> 1).single();
        return jdbc.sql("""
                        select exists (select 1 from core.notification where tenant_id = :t and recipient_user_id = :u
                                          and dedupe_key = :k and created_at > now() - interval '24 hours')""")
                .param("t", tenant).param("u", recipient).param("k", key).query(Boolean.class).single();
    }

    private UUID insertDelivery(UUID tenant, UUID notificationId, Timestamp notificationCreated, String channel,
            String destination, String status, boolean attachment, String error) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        insert into core.notification_delivery (tenant_id, id, created_at, notification_id,
                               notification_created_at, channel, destination_masked, status, has_attachment, last_error,
                               delivered_at)
                        values (:t, :id, :at, :n, :nat, :c, :dest, :s, :att, :err,
                                case when :s = 'DELIVERED' then cast(:at as timestamptz) end)""")
                .param("t", tenant).param("id", id).param("at", notificationCreated).param("n", notificationId)
                .param("nat", notificationCreated).param("c", channel).param("dest", destination).param("s", status)
                .param("att", attachment).param("err", error)
                .update();
        return id;
    }

    /** n***@acme.vn */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
