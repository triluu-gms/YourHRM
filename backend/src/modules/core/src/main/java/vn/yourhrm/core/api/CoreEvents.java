package vn.yourhrm.core.api;

import java.util.UUID;

/**
 * In-process domain events of Core (published with Spring's event publisher, after-commit listeners).
 * Transport through the transactional outbox ({@code core.outbox_event}) is deferred to TECH:outbox.
 */
public final class CoreEvents {

    private CoreEvents() {
    }

    /** {@code core.RoleAssignmentChanged}: a user's role assignments changed (grant / revoke). */
    public record RoleAssignmentChanged(UUID tenantId, UUID userAccountId, UUID assignmentId, String change) {
    }

    /** {@code core.RoleChanged}: a role or its permission matrix changed (affects every holder). */
    public record RoleChanged(UUID tenantId, UUID roleId, String change) {
    }

    /** {@code core.UserStatusChanged}: user created / activated / deactivated / identity relinked. */
    public record UserStatusChanged(UUID tenantId, UUID userAccountId, String status, String identityId) {
    }
}
