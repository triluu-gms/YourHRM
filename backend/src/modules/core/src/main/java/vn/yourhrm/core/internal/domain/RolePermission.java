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
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** One cell of a role's permission matrix ({@code core.role_permission}). */
@Entity
@Table(schema = "core", name = "role_permission")
@IdClass(RolePermission.Key.class)
public class RolePermission implements Persistable<RolePermission.Key> {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Id
    @Column(name = "permission_code", nullable = false, updatable = false)
    private String permissionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_mask_level", nullable = false)
    private FieldMaskLevel fieldMaskLevel;

    @Transient
    private boolean isNew = true;

    protected RolePermission() {
    }

    public RolePermission(UUID tenantId, UUID roleId, String permissionCode, FieldMaskLevel fieldMaskLevel) {
        this.tenantId = tenantId;
        this.roleId = roleId;
        this.permissionCode = permissionCode;
        this.fieldMaskLevel = fieldMaskLevel == null ? FieldMaskLevel.NONE : fieldMaskLevel;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public Key getId() {
        return new Key(tenantId, roleId, permissionCode);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void changeMask(FieldMaskLevel level) {
        this.fieldMaskLevel = level;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public FieldMaskLevel getFieldMaskLevel() {
        return fieldMaskLevel;
    }

    /** Composite key (tenant_id, role_id, permission_code). */
    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        private UUID tenantId;
        private UUID roleId;
        private String permissionCode;

        protected Key() {
        }

        public Key(UUID tenantId, UUID roleId, String permissionCode) {
            this.tenantId = tenantId;
            this.roleId = roleId;
            this.permissionCode = permissionCode;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(tenantId, k.tenantId) && Objects.equals(roleId, k.roleId)
                    && Objects.equals(permissionCode, k.permissionCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, roleId, permissionCode);
        }
    }
}
