package vn.yourhrm.core.internal.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key {@code (tenant_id, id)} of every tenant-owned table (DESIGN.md §4.1). */
public class TenantScopedId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID tenantId;
    private UUID id;

    protected TenantScopedId() {
    }

    public TenantScopedId(UUID tenantId, UUID id) {
        this.tenantId = tenantId;
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TenantScopedId other && Objects.equals(tenantId, other.tenantId)
                && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, id);
    }

    @Override
    public String toString() {
        return tenantId + "/" + id;
    }
}
