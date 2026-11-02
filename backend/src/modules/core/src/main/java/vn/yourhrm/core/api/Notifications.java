package vn.yourhrm.core.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;

/**
 * The notification centre (BR-CORE-070, interface P-14, US-CORE-071): modules never talk to a channel; they ask Core
 * to notify. Core resolves the recipients (users, or everyone holding a permission / role whose data scope covers a
 * target, through the single authorisation service), de-duplicates, renders the template in each recipient's
 * locale, writes the in-app item and hands e-mail to the SaaS delivery adapter after commit.
 *
 * <p>Runs in the caller's transaction (in-app items commit with the module's change).
 */
public interface Notifications {

    SendResult send(SendRequest request);

    /**
     * @param eventCode    a registered event code, e.g. {@code core.WorkflowTaskAssigned}
     * @param sourceModule the sending module (saas, core, hr, att, pay, rec)
     * @param payload      placeholder values (never personal data or amounts: those stay behind the deep link)
     * @param deepLink     app-relative link to the record, e.g. {@code /workflow/tasks/<id>}
     * @param dedupeKey    the same key for the same recipient within 24 hours is sent once (BR-CORE-073)
     * @param attachment   only for event codes registered as the payslip exception (BR-CORE-074), one recipient
     */
    record SendRequest(String eventCode, String sourceModule, Recipients recipients, Map<String, Object> payload,
            String sourceRefType, UUID sourceRefId, String deepLink, String dedupeKey, Attachment attachment) {

        public SendRequest {
            Objects.requireNonNull(eventCode, "eventCode");
            Objects.requireNonNull(sourceModule, "sourceModule");
            Objects.requireNonNull(recipients, "recipients");
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /**
     * Who receives it: listed users, plus the holders of a permission (or of a role) whose scope covers a target,
     * e.g. "HR officers responsible for this employee". External e-mail addresses are not supported in Phase 1
     * (every notification belongs to a tenant user's inbox).
     */
    record Recipients(Set<UUID> userIds, List<Audience> audiences) {

        public Recipients {
            userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
            audiences = audiences == null ? List.of() : List.copyOf(audiences);
        }

        public static Recipients users(UUID... ids) {
            return new Recipients(Set.of(ids), List.of());
        }
    }

    /** Holders of {@code permission}, or of role {@code roleCode}, whose scope covers {@code target}. */
    record Audience(String permission, String roleCode, AuthorizationTarget target) {

        public static Audience permission(String permission, AuthorizationTarget target) {
            return new Audience(permission, null, target);
        }

        public static Audience role(String roleCode, AuthorizationTarget target) {
            return new Audience(null, roleCode, target);
        }
    }

    /**
     * A password-protected PDF in the document service (the payslip exception, R2-17). The sender declares that the
     * file is encrypted with the employee's own password; Core refuses anything else.
     */
    record Attachment(UUID documentId, String fileName, String mediaType, String sha256,
            boolean encryptedWithRecipientPassword) {
    }

    /** {@code notificationIds}: one per recipient notified; {@code deduplicated}: recipients skipped as duplicates. */
    record SendResult(List<UUID> notificationIds, Set<UUID> deduplicated) {
    }
}
