package vn.yourhrm.core.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Company group profile ({@code core.company_group}, BR-CORE-001, US-CORE-001): exactly one per tenant
 * (DDL {@code company_group_one_per_tenant_uq}), created when the tenant is provisioned and editable by the
 * Tenant Owner. Holds the brand identity and the tenant defaults (locale, time zone, currency).
 */
@Entity
@Table(schema = "core", name = "company_group")
@IdClass(TenantScopedId.class)
public class CompanyGroup {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "logo_doc_id")
    private UUID logoDocumentId;

    @Column(name = "default_locale", nullable = false)
    private String defaultLocale;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "currency_code", nullable = false)
    private String currencyCode;

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

    protected CompanyGroup() {
    }

    public void update(String name, UUID logoDocumentId, String defaultLocale, String timeZone, String currencyCode,
            UUID actor, Instant now) {
        this.name = name;
        this.logoDocumentId = logoDocumentId;
        this.defaultLocale = defaultLocale;
        this.timeZone = timeZone;
        this.currencyCode = currencyCode;
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getLogoDocumentId() {
        return logoDocumentId;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public String getCurrencyCode() {
        return currencyCode;
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
