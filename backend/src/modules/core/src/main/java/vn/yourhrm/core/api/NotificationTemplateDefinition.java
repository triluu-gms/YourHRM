package vn.yourhrm.core.api;

import java.util.List;
import java.util.Objects;

/**
 * A vendor-maintained notification template (US-CORE-070): one event code × channel × locale. Placeholders are
 * written {@code {{name}}}; every placeholder used in the subject or body must be declared, and a declared
 * placeholder of kind {@link Placeholder.Kind#SENSITIVE} or {@link Placeholder.Kind#MONEY} is refused in every
 * template (BR-CORE-074: personal data and amounts never travel in notification text; a secure deep link does).
 *
 * @param category          SECURITY, WORKFLOW, PAYSLIP, REMINDER, ANNOUNCEMENT ...
 * @param mandatory         the user may not switch the category off (preferences are P2, BR-CORE-072)
 * @param channel           IN_APP or EMAIL in Phase 1
 * @param locale            vi-VN (required for every event and channel) or en-US
 * @param attachmentAllowed the registered exception of BR-CORE-074 (payslip): a password-protected PDF may be
 *                          attached to the e-mail
 */
public record NotificationTemplateDefinition(String eventCode, String category, boolean mandatory, String channel,
        String locale, String subject, String body, String bodyFormat, List<Placeholder> placeholders,
        boolean attachmentAllowed) {

    public NotificationTemplateDefinition {
        Objects.requireNonNull(eventCode, "eventCode");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(body, "body");
        bodyFormat = bodyFormat == null ? "TEXT" : bodyFormat;
        placeholders = placeholders == null ? List.of() : List.copyOf(placeholders);
    }

    /** A declared placeholder. {@code deepLink} is always available and needs no declaration. */
    public record Placeholder(String name, Kind kind) {

        public enum Kind {
            TEXT, DATE, NUMBER, LINK, SENSITIVE, MONEY
        }
    }
}
