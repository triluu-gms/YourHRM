package vn.yourhrm.core.internal.domain.authz;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import vn.yourhrm.core.internal.domain.FieldMaskLevel;
import vn.yourhrm.shared.auth.api.ScopeType;

/**
 * Effective authorisation snapshot of one user (ADR-0006 decision 1): every non-expired role assignment
 * with the role's permission matrix. Cached; validity is evaluated per check against the business date,
 * so a cached snapshot never grants an assignment outside its period.
 *
 * @param tenantId          tenant
 * @param userAccountId     user
 * @param selfEmploymentId  the user's own employment (People, soft reference), may be {@code null}
 * @param assignments       assignments not yet expired at load time (future ones included)
 */
public record EffectiveAuthorisation(UUID tenantId, UUID userAccountId, UUID selfEmploymentId,
        List<AssignmentGrant> assignments) {

    public EffectiveAuthorisation {
        assignments = List.copyOf(assignments);
    }

    /** Assignments valid on {@code date}. */
    public Stream<AssignmentGrant> activeOn(LocalDate date) {
        return assignments.stream().filter(a -> a.isActiveOn(date));
    }

    /** Grants (scopes) carrying {@code permission} valid on {@code date}. */
    public List<AssignmentGrant> grantsFor(String permission, LocalDate date) {
        return activeOn(date).filter(a -> a.permissions().containsKey(permission)).toList();
    }

    /**
     * One role assignment and the permissions of its role.
     *
     * @param permissions permission code -> field mask level of the role's matrix
     */
    public record AssignmentGrant(
            UUID assignmentId,
            UUID roleId,
            String roleCode,
            String roleNameVi,
            String roleNameEn,
            ScopeType scopeType,
            Set<UUID> scopeIds,
            boolean includeSubtree,
            LocalDate validFrom,
            LocalDate validTo,
            Map<String, FieldMaskLevel> permissions) {

        public AssignmentGrant {
            scopeIds = Set.copyOf(scopeIds);
            permissions = Map.copyOf(permissions);
        }

        public boolean isActiveOn(LocalDate date) {
            return !validFrom.isAfter(date) && (validTo == null || validTo.isAfter(date));
        }
    }
}
