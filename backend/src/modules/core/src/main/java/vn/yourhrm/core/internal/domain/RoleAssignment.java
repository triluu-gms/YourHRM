package vn.yourhrm.core.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import vn.yourhrm.shared.auth.api.ScopeType;

/**
 * A role granted to a user with a data scope and a validity period ({@code core.role_assignment},
 * US-CORE-035). {@code valid_to} is exclusive ({@code [valid_from, valid_to)}, DESIGN.md §5.1).
 * The DDL has no updated_* / version columns: an assignment is revoked by closing its period (or deleted
 * when it never took effect).
 */
@Entity
@Table(schema = "core", name = "role_assignment")
@IdClass(TenantScopedId.class)
public class RoleAssignment implements Persistable<TenantScopedId> {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, updatable = false)
    private ScopeType scopeType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "scope_ids", nullable = false, updatable = false, columnDefinition = "uuid[]")
    private UUID[] scopeIds;

    @Column(name = "include_subtree", nullable = false, updatable = false)
    private boolean includeSubtree;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "granted_by", updatable = false)
    private UUID grantedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Transient
    private boolean isNew = true;

    protected RoleAssignment() {
    }

    public static RoleAssignment grant(UUID tenantId, UUID id, UUID userId, UUID roleId, ScopeType scopeType,
            Set<UUID> scopeIds, boolean includeSubtree, LocalDate validFrom, LocalDate validTo, UUID actor,
            Instant now) {
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
        if (scopeType.requiresIds() == scopeIds.isEmpty()) {
            throw new IllegalArgumentException(scopeType.requiresIds()
                    ? "scopeIds are required for scope " + scopeType
                    : "scopeIds must be empty for scope " + scopeType);
        }
        RoleAssignment a = new RoleAssignment();
        a.tenantId = tenantId;
        a.id = id;
        a.userId = userId;
        a.roleId = roleId;
        a.scopeType = scopeType;
        a.scopeIds = scopeIds.toArray(UUID[]::new);
        a.includeSubtree = includeSubtree;
        a.validFrom = validFrom;
        a.validTo = validTo;
        a.grantedBy = actor;
        a.createdAt = now;
        a.createdBy = actor;
        return a;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public TenantScopedId getId() {
        return new TenantScopedId(tenantId, id);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public boolean isActiveOn(LocalDate date) {
        return !validFrom.isAfter(date) && (validTo == null || validTo.isAfter(date));
    }

    /** True when the assignment never took effect before {@code today} (it can simply be deleted). */
    public boolean startsOnOrAfter(LocalDate today) {
        return !validFrom.isBefore(today);
    }

    /** Ends the assignment at the start of {@code today} (exclusive end), so it no longer grants today. */
    public void endBefore(LocalDate today) {
        if (validTo == null || validTo.isAfter(today)) {
            this.validTo = today;
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getAssignmentId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public Set<UUID> getScopeIds() {
        return new LinkedHashSet<>(Arrays.asList(scopeIds));
    }

    public boolean isIncludeSubtree() {
        return includeSubtree;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
