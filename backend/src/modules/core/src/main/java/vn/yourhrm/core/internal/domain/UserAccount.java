package vn.yourhrm.core.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Tenant user account ({@code core.user_account}, US-CORE-030). Authentication data (credentials, MFA)
 * lives in Keycloak; {@link #identityId} links the account to the Keycloak user (ADR-0005 decision 6).
 */
@Entity
@Table(schema = "core", name = "user_account")
@IdClass(TenantScopedId.class)
public class UserAccount {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "login", nullable = false)
    private String login;

    @Column(name = "email")
    private String email;

    @Column(name = "mobile")
    private String mobile;

    @Column(name = "employment_id")
    private UUID employmentId;

    @Column(name = "person_id")
    private UUID personId;

    @Column(name = "identity_id")
    private UUID identityId;

    @Column(name = "locale", nullable = false)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "access_revoked_at")
    private Instant accessRevokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected UserAccount() {
    }

    public static UserAccount create(UUID tenantId, UUID id, String login, String locale, UUID actor, Instant now) {
        UserAccount u = new UserAccount();
        u.tenantId = tenantId;
        u.id = id;
        u.login = login;
        u.locale = locale;
        u.status = UserStatus.ACTIVE;
        u.createdAt = now;
        u.createdBy = actor;
        u.updatedAt = now;
        u.updatedBy = actor;
        return u;
    }

    public void updateProfile(String email, String mobile, String locale, UUID identityId, UUID employmentId,
            UUID personId, Instant expiresAt, UUID actor, Instant now) {
        this.email = email;
        this.mobile = mobile;
        this.locale = locale;
        this.identityId = identityId;
        this.employmentId = employmentId;
        this.personId = personId;
        this.expiresAt = expiresAt;
        touch(actor, now);
    }

    public void activate(UUID actor, Instant now) {
        this.status = UserStatus.ACTIVE;
        this.accessRevokedAt = null;
        touch(actor, now);
    }

    public void deactivate(UUID actor, Instant now) {
        this.status = UserStatus.DEACTIVATED;
        this.accessRevokedAt = now;
        touch(actor, now);
    }

    /** May sign in now: status allows it and the account has not expired. */
    public boolean mayAccess(Instant now) {
        return status.mayAccess() && (expiresAt == null || expiresAt.isAfter(now));
    }

    private void touch(UUID actor, Instant now) {
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getId() {
        return id;
    }

    public String getLogin() {
        return login;
    }

    public String getEmail() {
        return email;
    }

    public String getMobile() {
        return mobile;
    }

    public UUID getEmploymentId() {
        return employmentId;
    }

    public UUID getPersonId() {
        return personId;
    }

    public UUID getIdentityId() {
        return identityId;
    }

    public String getLocale() {
        return locale;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAccessRevokedAt() {
        return accessRevokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Integer getVersion() {
        return version;
    }
}
