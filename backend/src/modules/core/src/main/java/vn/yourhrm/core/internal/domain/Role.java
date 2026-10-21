package vn.yourhrm.core.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Tenant role ({@code core.role}, US-CORE-034). System roles are seeded per tenant from a template and are
 * read-only. NOTE: the DDL table has no created_by / updated_by columns; the acting user of a role change is
 * recorded only for the permission matrix (row audit on core.role_permission). Reported as a DDL gap.
 */
@Entity
@Table(schema = "core", name = "role")
@IdClass(TenantScopedId.class)
public class Role {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name_vi", nullable = false)
    private String nameVi;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "is_system", nullable = false, updatable = false)
    private boolean system;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Role() {
    }

    public static Role createCustom(UUID tenantId, UUID id, String code, String nameVi, String nameEn, Instant now) {
        Role r = new Role();
        r.tenantId = tenantId;
        r.id = id;
        r.code = code;
        r.nameVi = nameVi;
        r.nameEn = nameEn;
        r.system = false;
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    /** Rename a custom role. System roles are immutable (US-CORE-034 AC). */
    public void rename(String nameVi, String nameEn, Instant now) {
        assertMutable();
        this.nameVi = nameVi;
        this.nameEn = nameEn;
        this.updatedAt = now;
    }

    public void assertMutable() {
        if (system) {
            throw new SystemRoleImmutableException(code);
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getNameVi() {
        return nameVi;
    }

    public String getNameEn() {
        return nameEn;
    }

    public boolean isSystem() {
        return system;
    }

    public Integer getTemplateVersion() {
        return templateVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Integer getVersion() {
        return version;
    }

    /** Raised when a system role would be changed or deleted. */
    public static class SystemRoleImmutableException extends ApiException {
        private static final long serialVersionUID = 1L;

        public SystemRoleImmutableException(String code) {
            super(HttpStatus.CONFLICT, "system-role-immutable",
                    "System role " + code + " is read-only; copy it to create a custom role");
        }
    }
}
