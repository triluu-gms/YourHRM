package vn.yourhrm.core.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The tenant audit store (BR-CORE-090/091, interfaces P-20 and P-27; ADR-0027). Core is the only tenant audit store.
 *
 * <p>Data changes are audited by the database: a module attaches {@code audit.attach_row_audit(...)} to the tables it
 * declares as audited, and every INSERT / UPDATE / DELETE is recorded in the same transaction with the masked diff,
 * the actor, the support operator, and the request origin (channel UI / API / IMPORT / SYSTEM, client address, user
 * agent, correlation id). This API adds what a trigger cannot see:
 * <ul>
 *   <li>{@link #record}: a business action of the caller (approve, generate, export, ...), in the caller's
 *       transaction, so the action and its audit commit or roll back together;</li>
 *   <li>{@link #recordSecurityEvent}: a security event (P-27), e.g. a sign-in reported by the SaaS identity layer.
 *       It is written in its own transaction so that it survives a rolled-back request (a failed login is still
 *       evidence).</li>
 * </ul>
 * Details are stored as given: callers never put P2/P3 values (amounts, identity numbers) into them (D7).
 */
public interface AuditLog {

    void record(BusinessAction action);

    void recordSecurityEvent(SecurityEvent event);

    /**
     * @param action      an upper-case verb such as {@code APPROVE}, {@code GENERATE}, {@code EXPORT}; data changes
     *                    ({@code INSERT}/{@code UPDATE}/{@code DELETE}) belong to the row audit
     * @param module      owner module of the object: saas, core, hr, att, pay or rec (the audit "module" filter)
     * @param objectType  e.g. {@code payroll_run}, {@code document}
     */
    record BusinessAction(String action, String module, String objectType, UUID objectId, UUID legalEntityId,
            Map<String, String> details) {

        public BusinessAction {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(objectType, "objectType");
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    /** The fixed security event types of BR-CORE-091. */
    enum SecurityEventType {
        LOGIN_SUCCEEDED, LOGIN_FAILED, LOGOUT, MFA_ENROLLED, MFA_FAILED, MFA_RESET, PASSWORD_CHANGED, PASSWORD_RESET,
        SESSION_REVOKED, SSO_CONFIG_CHANGED, BREAK_GLASS_STARTED, BREAK_GLASS_ENDED, PERSONAL_DATA_EXPORTED,
        PERSONAL_DATA_DOWNLOADED, SENSITIVE_FIELD_VIEWED
    }

    /**
     * A security event of a tenant (BR-CORE-091). Platform events not tied to a tenant go to {@code saas} audit, not
     * here. Role and permission changes need no call: they are row-audited ({@code core.role_permission},
     * {@code core.role_assignment}) and reported as security events by the audit search.
     *
     * @param userAccountId the tenant user concerned, when known (null e.g. for a failed login of an unknown name)
     * @param identityId    the identity (Keycloak subject or login name) as reported by SaaS
     * @param operatorId    a platform operator, for back-office and break-glass events
     */
    record SecurityEvent(UUID tenantId, SecurityEventType type, UUID userAccountId, String identityId,
            UUID operatorId, String clientIp, String userAgent, Map<String, String> details) {

        public SecurityEvent {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(type, "type");
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
