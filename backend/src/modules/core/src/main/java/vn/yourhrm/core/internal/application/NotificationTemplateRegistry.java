package vn.yourhrm.core.internal.application;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.shared.common.tenancy.PlatformContext;

/**
 * Vendor-maintained notification templates (US-CORE-070; tenant overrides are P2, BR-CORE-071). The templates of all
 * modules are validated when the context starts, so a broken template fails the deploy, not a send:
 * <ul>
 *   <li>every {@code {{placeholder}}} used must be declared ({@code deepLink} is implicit);</li>
 *   <li>SENSITIVE and MONEY placeholders are refused in every channel (BR-CORE-074: deep link instead);</li>
 *   <li>channels are IN_APP / EMAIL (Phase 1), locales vi-VN / en-US, and each event × channel has a vi-VN text;</li>
 *   <li>an attachment may be allowed only on EMAIL templates (the payslip exception).</li>
 * </ul>
 * The global table {@code core.notification_template} is written by the release job (the runtime role cannot write
 * sentinel-tenant rows); the SQL is logged when it lags behind the code.
 */
@Component
public class NotificationTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotificationTemplateRegistry.class);
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");
    private static final Set<String> CHANNELS = Set.of("IN_APP", "EMAIL");
    private static final Set<String> LOCALES = Set.of("vi-VN", "en-US");
    public static final String DEFAULT_LOCALE = "vi-VN";

    private final Map<String, NotificationTemplateDefinition> byKey = new HashMap<>();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public NotificationTemplateRegistry(List<NotificationTemplateCatalogue> catalogues, JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        for (NotificationTemplateCatalogue c : catalogues) {
            for (NotificationTemplateDefinition t : c.templates()) {
                validate(c.module(), t);
                if (byKey.put(key(t.eventCode(), t.channel(), t.locale()), t) != null) {
                    throw new IllegalStateException("Duplicate template " + key(t.eventCode(), t.channel(), t.locale()));
                }
            }
        }
        byKey.values().forEach(t -> {
            if (!byKey.containsKey(key(t.eventCode(), t.channel(), DEFAULT_LOCALE))) {
                throw new IllegalStateException("Template " + t.eventCode() + "/" + t.channel() + " has no vi-VN text");
            }
            // every notification is an inbox item; e-mail is an extra channel of it
            if (!byKey.containsKey(key(t.eventCode(), "IN_APP", DEFAULT_LOCALE))) {
                throw new IllegalStateException("Event " + t.eventCode() + " has no IN_APP template");
            }
        });
    }

    /** The template for a recipient's locale, falling back to vi-VN; empty when the event has no such channel. */
    public Optional<NotificationTemplateDefinition> find(String eventCode, String channel, String locale) {
        NotificationTemplateDefinition t = byKey.get(key(eventCode, channel, locale));
        return Optional.ofNullable(t != null ? t : byKey.get(key(eventCode, channel, DEFAULT_LOCALE)));
    }

    public boolean isRegistered(String eventCode) {
        return byKey.values().stream().anyMatch(t -> t.eventCode().equals(eventCode));
    }

    public List<NotificationTemplateDefinition> all() {
        return byKey.values().stream()
                .sorted((a, b) -> key(a.eventCode(), a.channel(), a.locale())
                        .compareTo(key(b.eventCode(), b.channel(), b.locale())))
                .toList();
    }

    /** Compares the code templates with the global rows and logs the release-job SQL for the missing ones. */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyGlobalRows() throws Exception {
        if (byKey.isEmpty()) {
            return;
        }
        List<String> missing = PlatformContext.call("NOTIFICATION_TEMPLATE_REGISTRATION", () -> tx.execute(s ->
                all().stream().filter(t -> !jdbc.sql("""
                                select exists (select 1 from core.notification_template
                                                where tenant_id = sys.global_tenant_id() and event_code = :e
                                                  and channel = :c and locale = :l and is_active and body = :b)""")
                        .param("e", t.eventCode()).param("c", t.channel()).param("l", t.locale()).param("b", t.body())
                        .query(Boolean.class).single()).map(t -> key(t.eventCode(), t.channel(), t.locale())).toList()));
        if (!missing.isEmpty()) {
            log.info("{} notification template(s) are rendered from code until the release job registers them: {}",
                    missing.size(), missing);
        }
    }

    /** Placeholders used by the subject and body. */
    static Set<String> used(NotificationTemplateDefinition t) {
        Set<String> out = new LinkedHashSet<>();
        for (String text : new String[] {t.subject(), t.body()}) {
            if (text == null) {
                continue;
            }
            Matcher m = PLACEHOLDER.matcher(text);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    static void validate(String module, NotificationTemplateDefinition t) {
        String id = t.eventCode() + "/" + t.channel() + "/" + t.locale();
        if (!t.eventCode().startsWith(module + ".")) {
            throw new IllegalStateException("Template " + id + ": event code must start with " + module + ".");
        }
        if (!CHANNELS.contains(t.channel())) {
            throw new IllegalStateException("Template " + id + ": channel must be IN_APP or EMAIL in Phase 1");
        }
        if (!LOCALES.contains(t.locale())) {
            throw new IllegalStateException("Template " + id + ": locale must be vi-VN or en-US");
        }
        if (t.subject() == null || t.subject().isBlank()) {
            throw new IllegalStateException("Template " + id + ": a subject / title is required");
        }
        Map<String, Placeholder.Kind> declared = new HashMap<>();
        t.placeholders().forEach(p -> declared.put(p.name(), p.kind()));
        for (Placeholder p : t.placeholders()) {
            if (p.kind() == Placeholder.Kind.SENSITIVE || p.kind() == Placeholder.Kind.MONEY) {
                throw new IllegalStateException("Template " + id + ": placeholder " + p.name() + " is " + p.kind()
                        + "; personal data and amounts never go into notification text (BR-CORE-074), use the deep link");
            }
        }
        for (String name : used(t)) {
            if (!"deepLink".equals(name) && !declared.containsKey(name)) {
                throw new IllegalStateException("Template " + id + ": placeholder {{" + name + "}} is not declared");
            }
        }
        if (t.attachmentAllowed() && !"EMAIL".equals(t.channel())) {
            throw new IllegalStateException("Template " + id + ": attachments exist only on e-mail");
        }
    }

    static String key(String eventCode, String channel, String locale) {
        return eventCode + "|" + channel + "|" + locale;
    }
}
