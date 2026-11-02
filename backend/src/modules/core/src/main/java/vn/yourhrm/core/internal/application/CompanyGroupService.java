package vn.yourhrm.core.internal.application;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CompanyGroupProvisioning;
import vn.yourhrm.core.internal.domain.CompanyGroup;
import vn.yourhrm.core.internal.infra.CompanyGroupRepository;
import vn.yourhrm.core.internal.infra.MasterDataQueries;
import vn.yourhrm.core.internal.infra.MasterDataQueries.TenantInfo;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Company group profile (BR-CORE-001, US-CORE-001): created idempotently at provisioning, then maintained by the
 * Tenant Owner. Changes are recorded by the DDL row-audit trigger on core.company_group (NFR-CORE-008).
 */
@Service
public class CompanyGroupService implements CompanyGroupProvisioning {

    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}-[A-Z]{2}$");

    private final CompanyGroupRepository groups;
    private final OrgUnitTypeService orgUnitTypes;
    private final EmployeeCategoryService employeeCategories;
    private final SystemRoleService systemRoles;
    private final MasterDataQueries masterData;
    private final JdbcClient jdbc;
    private final BusinessClock clock;

    public CompanyGroupService(CompanyGroupRepository groups, OrgUnitTypeService orgUnitTypes,
            EmployeeCategoryService employeeCategories, SystemRoleService systemRoles, MasterDataQueries masterData,
            JdbcClient jdbc, BusinessClock clock) {
        this.groups = groups;
        this.orgUnitTypes = orgUnitTypes;
        this.employeeCategories = employeeCategories;
        this.systemRoles = systemRoles;
        this.masterData = masterData;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record CompanyGroupView(UUID id, String name, UUID logoDocumentId, String defaultLocale, String timeZone,
            String currencyCode, Instant updatedAt, UUID updatedBy, int version) {

        static CompanyGroupView of(CompanyGroup g) {
            return new CompanyGroupView(g.getId(), g.getName(), g.getLogoDocumentId(), g.getDefaultLocale(),
                    g.getTimeZone(), g.getCurrencyCode(), g.getUpdatedAt(), g.getUpdatedBy(),
                    g.getVersion() == null ? 0 : g.getVersion());
        }
    }

    public record Profile(String name, UUID logoDocumentId, String defaultLocale, String timeZone,
            String currencyCode) {
    }

    @Override
    @Transactional
    public boolean provision() {
        UUID tenant = TenantContext.current().tenantId();
        TenantInfo info = masterData.tenant(tenant)
                .orElseThrow(() -> new IllegalStateException("Tenant " + tenant + " has no saas.tenant row"));
        int inserted = jdbc.sql("""
                        insert into core.company_group (tenant_id, id, name, default_locale, time_zone, currency_code,
                                                        created_by, updated_by)
                        values (:t, :id, :name, coalesce(:locale, 'vi-VN'), coalesce(:tz, 'Asia/Ho_Chi_Minh'), 'VND',
                                :actor, :actor)
                        on conflict (tenant_id) do nothing""")
                .param("t", tenant).param("id", Ids.newId()).param("name", info.name())
                .param("locale", info.defaultLocale()).param("tz", info.timeZone())
                .param("actor", TenantContext.current().userAccountId())
                .update();
        orgUnitTypes.seedDefaults(); // US-CORE-010: default org unit types (idempotent)
        employeeCategories.seedDefaults(); // US-CORE-023: default employee categories (idempotent)
        systemRoles.syncSystemRoles(); // US-CORE-033: system roles from the vendor templates (idempotent)
        return inserted == 1;
    }

    @Transactional(readOnly = true)
    public CompanyGroupView get() {
        return CompanyGroupView.of(load());
    }

    @Transactional
    public CompanyGroupView update(Profile profile, int expectedVersion) {
        TenantContext ctx = TenantContext.current();
        CompanyGroup group = load();
        if (group.getVersion() != expectedVersion) {
            throw ApiException.conflict("stale-version",
                    "The company profile was changed (version " + group.getVersion() + "); reload and retry");
        }
        validate(ctx.tenantId(), profile);
        group.update(profile.name().strip(), profile.logoDocumentId(), profile.defaultLocale(), profile.timeZone(),
                profile.currencyCode(), ctx.userAccountId(), clock.now());
        groups.saveAndFlush(group);
        return CompanyGroupView.of(group);
    }

    private void validate(UUID tenant, Profile p) {
        if (!LOCALE.matcher(p.defaultLocale()).matches()) {
            throw ApiException.unprocessable("invalid-locale", "Locale must look like vi-VN, got: " + p.defaultLocale());
        }
        try {
            ZoneId.of(p.timeZone());
        } catch (DateTimeException e) {
            throw ApiException.unprocessable("invalid-time-zone", "Unknown time zone: " + p.timeZone());
        }
        try {
            Currency.getInstance(p.currencyCode());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid-currency", "Unknown ISO 4217 currency: " + p.currencyCode());
        }
        // The logo is a document of the tenant's document service (US-CORE-100); only its reference is kept here.
        if (p.logoDocumentId() != null && !jdbc.sql("""
                        select exists (select 1 from core.document where tenant_id = :t and id = :id and status = 'ACTIVE')""")
                .param("t", tenant).param("id", p.logoDocumentId()).query(Boolean.class).single()) {
            throw ApiException.unprocessable("unknown-document", "Logo document " + p.logoDocumentId() + " not found");
        }
    }

    private CompanyGroup load() {
        UUID tenant = TenantContext.current().tenantId();
        return groups.findByTenantId(tenant).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "company-group-not-provisioned",
                "The company profile of this tenant has not been provisioned yet"));
    }
}
