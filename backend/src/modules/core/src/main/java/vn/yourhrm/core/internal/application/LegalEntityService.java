package vn.yourhrm.core.internal.application;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CoreEvents.LegalEntityChanged;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.domain.LegalEntityStatus;
import vn.yourhrm.core.internal.domain.PitDeclarationFrequency;
import vn.yourhrm.core.internal.domain.TaxCode;
import vn.yourhrm.core.internal.infra.EffectiveDatedVersions;
import vn.yourhrm.core.internal.infra.EffectiveDatedVersions.Slot;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Legal entities of the tenant: 1..n within the plan limit (BR-CORE-001), employer master data (BR-CORE-002),
 * MST format and uniqueness (BR-CORE-003) and effective-dated statutory attributes with as-of history
 * (BR-CORE-004, US-CORE-002/003/004). Every attribute lives on {@code core.legal_entity_version}; a change from
 * date D splits the version covering D ({@link EffectiveDatedVersions}). Reads are filtered by the caller's data
 * scope of {@code core.legal_entity.view}; writes on an existing entity need {@code core.legal_entity.update}
 * on that entity (BR-CORE-035).
 */
@Service
public class LegalEntityService {

    private static final ScopePredicate.Columns SCOPE_COLUMNS = new ScopePredicate.Columns("le.id", null, null, null);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+().\\s-]{6,20}$");
    private static final MonthDay TAX_YEAR_START = MonthDay.of(1, 1);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final LegalEntityLimitPolicy limits;
    private final EffectiveDatedVersions versions;
    private final MinWageRegionService regions;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;

    public LegalEntityService(JdbcClient jdbc, AuthorizationService authz, LegalEntityLimitPolicy limits,
            EffectiveDatedVersions versions, MinWageRegionService regions, ApplicationEventPublisher events,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.limits = limits;
        this.versions = versions;
        this.regions = regions;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The full attribute set of one version (PUT semantics: what is in effect from the version's start).
     * {@code oaOdRate} null = the statutory standard BHTNLĐ-BNN rate of the legal pack; a reduced rate needs the
     * decision reference and its evidence document (US-CORE-004).
     */
    public record Attributes(String legalNameVi, String legalNameEn, String shortName, String taxCode,
            String siUnitCode, String businessRegNo, LocalDate businessRegDate, String businessRegIssuer,
            String phone, String email, String adminUnitCode, String addressLine, String legalRepName,
            String legalRepTitle, Integer fiscalYearStartMonth, String taxAuthorityCode,
            PitDeclarationFrequency pitDeclarationFrequency, BigDecimal oaOdRate, String oaOdDecisionRef,
            UUID oaOdDecisionDocId) {
    }

    public record VersionView(UUID versionId, LocalDate validFrom, LocalDate validTo, String legalNameVi,
            String legalNameEn, String shortName, String taxCode, String siUnitCode, String businessRegNo,
            LocalDate businessRegDate, String businessRegIssuer, String phone, String email, String adminUnitCode,
            String addressLine, String legalRepName, String legalRepTitle, int fiscalYearStartMonth,
            String taxAuthorityCode, PitDeclarationFrequency pitDeclarationFrequency, BigDecimal oaOdRate,
            String oaOdDecisionRef, UUID oaOdDecisionDocId) {
    }

    /** The anchor plus the version in effect on the requested date (or the nearest one, see {@link #SELECT}). */
    public record LegalEntityView(UUID id, String code, LegalEntityStatus status, int version, VersionView current) {
    }

    /** How many legal entities the tenant has and may have (shown next to "create" in the UI). */
    public record Usage(long used, Long limit) {
    }

    @Transactional(readOnly = true)
    public List<LegalEntityView> list(LocalDate asOf, LegalEntityStatus status) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        SqlFragment scope = authz.scopeFor(CorePermissions.LEGAL_ENTITY_VIEW, date).toSql(SCOPE_COLUMNS, "s");
        return jdbc.sql(SELECT + " where le.tenant_id = :t and (cast(:st as text) is null or le.status = :st) and "
                        + scope.sql() + " order by le.code")
                .param("t", TenantContext.current().tenantId()).param("d", date)
                .param("st", status == null ? null : status.name())
                .params(scope.params())
                .query((rs, n) -> map(rs)).list();
    }

    @Transactional(readOnly = true)
    public LegalEntityView get(UUID id, LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        assertCan(CorePermissions.LEGAL_ENTITY_VIEW, id, date);
        return find(id, date).orElseThrow(() -> ApiException.notFound("Legal entity", id));
    }

    /** The whole effective-dated history, oldest first (US-CORE-003 history screen). */
    @Transactional(readOnly = true)
    public List<VersionView> history(UUID id) {
        assertCan(CorePermissions.LEGAL_ENTITY_VIEW, id, clock.today());
        List<VersionView> history = jdbc.sql("select * from core.legal_entity_version v"
                        + " where v.tenant_id = :t and v.legal_entity_id = :id order by v.valid_from")
                .param("t", TenantContext.current().tenantId()).param("id", id)
                .query((rs, n) -> mapVersion(rs)).list();
        if (history.isEmpty()) {
            throw ApiException.notFound("Legal entity", id);
        }
        return history;
    }

    @Transactional(readOnly = true)
    public Usage usage() {
        UUID tenant = TenantContext.current().tenantId();
        OptionalLong max = limits.maxLegalEntities();
        return new Usage(countCounted(tenant), max.isPresent() ? max.getAsLong() : null);
    }

    @Transactional
    public LegalEntityView create(String code, LegalEntityStatus status, LocalDate validFrom, Attributes a) {
        TenantContext ctx = TenantContext.current();
        UUID tenant = ctx.tenantId();
        LegalEntityStatus initial = status == null ? LegalEntityStatus.ACTIVE : status;
        if (initial == LegalEntityStatus.DISSOLVED) {
            throw ApiException.unprocessable("invalid-status", "A legal entity cannot be created as DISSOLVED");
        }
        LocalDate from = validFrom == null ? clock.today() : validFrom;
        // Serialises legal-entity creation per tenant, so the limit and the MST uniqueness checks below
        // cannot be raced by a concurrent create (there is no DB constraint for either).
        lockTenantLegalEntities(tenant);

        OptionalLong max = limits.maxLegalEntities();
        long used = countCounted(tenant);
        if (max.isPresent() && used >= max.getAsLong()) {
            throw limitReached(max.getAsLong(), used);
        }
        if (jdbc.sql("select exists (select 1 from core.legal_entity where tenant_id = :t and code = :c)")
                .param("t", tenant).param("c", code).query(Boolean.class).single()) {
            throw ApiException.conflict("legal-entity-code-taken", "A legal entity with code " + code + " already exists");
        }
        UUID id = Ids.newId();
        Attributes valid = validate(tenant, id, from, a);

        jdbc.sql("""
                        insert into core.legal_entity (tenant_id, id, code, status, created_by, updated_by)
                        values (:t, :id, :code, :status, :actor, :actor)""")
                .param("t", tenant).param("id", id).param("code", code).param("status", initial.name())
                .param("actor", ctx.userAccountId())
                .update();
        insertVersion(tenant, id, from, null, null, valid);
        events.publishEvent(new LegalEntityChanged(tenant, id, "CREATED", from));
        return find(id, from).orElseThrow();
    }

    /**
     * Applies {@code a} from {@code effectiveFrom} (BR-CORE-004, US-CORE-003): corrects the version starting on that
     * date, or ends the covering version at D-1 and starts a new one at D that keeps the old end date.
     */
    @Transactional
    public LegalEntityView changeFrom(UUID id, LocalDate effectiveFrom, Attributes a, int expectedVersion) {
        TenantContext ctx = TenantContext.current();
        UUID tenant = ctx.tenantId();
        assertCan(CorePermissions.LEGAL_ENTITY_UPDATE, id, effectiveFrom);
        lockTenantLegalEntities(tenant); // MST uniqueness is checked across entities
        versions.checkAndBump(EffectiveDatedVersions.LEGAL_ENTITY, tenant, id, expectedVersion, ctx.userAccountId(),
                "Legal entity " + id);
        Slot slot = versions.slotFor(EffectiveDatedVersions.LEGAL_ENTITY, tenant, id, effectiveFrom);
        Attributes valid = validate(tenant, id, effectiveFrom, a);
        String previousAdminUnit = jdbc.sql(
                        "select admin_unit_code from core.legal_entity_version where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", slot.covering().id()).query((rs, n) -> Optional.ofNullable(rs.getString(1))).single().orElse(null);
        if (slot.split()) {
            versions.closeAt(EffectiveDatedVersions.LEGAL_ENTITY, tenant, slot.covering().id(), effectiveFrom);
            insertVersion(tenant, id, effectiveFrom, slot.covering().validTo(), slot.covering().id(), valid);
        } else {
            correctVersion(tenant, slot.covering().id(), valid);
        }
        assertOnePitFrequencyPerTaxYear(tenant, id);
        events.publishEvent(new LegalEntityChanged(tenant, id, "CHANGED", effectiveFrom));
        regions.legalEntityAddressChanged(id, effectiveFrom, previousAdminUnit, valid.adminUnitCode());
        return find(id, effectiveFrom).orElseThrow();
    }

    /**
     * Status lifecycle DRAFT -> ACTIVE -> DISSOLVED (DRAFT -> DISSOLVED allowed). DISSOLVED is final and needs
     * every location of the entity to be closed first. Status is a property of the anchor (not effective-dated).
     */
    @Transactional
    public LegalEntityView changeStatus(UUID id, LegalEntityStatus target, int expectedVersion) {
        TenantContext ctx = TenantContext.current();
        UUID tenant = ctx.tenantId();
        assertCan(CorePermissions.LEGAL_ENTITY_UPDATE, id, clock.today());
        LegalEntityView current = find(id, clock.today()).orElseThrow(() -> ApiException.notFound("Legal entity", id));
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("stale-version", "Legal entity " + current.code() + " was changed (version "
                    + current.version() + "); reload and retry");
        }
        LegalEntityStatus from = current.status();
        boolean allowed = from != target && from != LegalEntityStatus.DISSOLVED && target != LegalEntityStatus.DRAFT;
        if (!allowed) {
            throw ApiException.unprocessable("invalid-status-transition",
                    "Cannot change a legal entity from " + from + " to " + target);
        }
        if (target == LegalEntityStatus.DISSOLVED && jdbc.sql("""
                        select exists (select 1 from core.location
                                        where tenant_id = :t and legal_entity_id = :id and status = 'ACTIVE')""")
                .param("t", tenant).param("id", id).query(Boolean.class).single()) {
            throw ApiException.conflict("legal-entity-has-active-locations",
                    "Close every location of " + current.code() + " before dissolving it");
        }
        int updated = jdbc.sql("""
                        update core.legal_entity set status = :s, version = version + 1, updated_by = :actor
                         where tenant_id = :t and id = :id and version = :v""")
                .param("s", target.name()).param("actor", ctx.userAccountId())
                .param("t", tenant).param("id", id).param("v", expectedVersion)
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale-version", "Legal entity " + current.code() + " was changed; reload and retry");
        }
        events.publishEvent(new LegalEntityChanged(tenant, id, "STATUS_" + target.name(), clock.today()));
        return find(id, clock.today()).orElseThrow();
    }

    private Attributes validate(UUID tenant, UUID id, LocalDate validFrom, Attributes a) {
        TaxCode taxCode = TaxCode.parse(a.taxCode());
        if (jdbc.sql("""
                        select exists (select 1 from core.legal_entity_version
                                        where tenant_id = :t and tax_code = :tc and legal_entity_id <> :id)""")
                .param("t", tenant).param("tc", taxCode.value()).param("id", id).query(Boolean.class).single()) {
            throw ApiException.conflict("tax-code-taken",
                    "Tax code " + taxCode.value() + " is already used by another legal entity of this tenant");
        }
        if (a.adminUnitCode() != null && !jdbc.sql("""
                        select exists (select 1 from core.admin_unit where admin_unit_code = :c
                                          and valid_from <= :d and (valid_to is null or valid_to > :d))""")
                .param("c", a.adminUnitCode()).param("d", validFrom).query(Boolean.class).single()) {
            throw ApiException.unprocessable("unknown-admin-unit",
                    "Administrative unit " + a.adminUnitCode() + " does not exist or is not valid on " + validFrom);
        }
        if (a.email() != null && !EMAIL.matcher(a.email()).matches()) {
            throw ApiException.unprocessable("invalid-email", "Invalid e-mail address: " + a.email());
        }
        if (a.phone() != null && !PHONE.matcher(a.phone()).matches()) {
            throw ApiException.unprocessable("invalid-phone", "Invalid phone number: " + a.phone());
        }
        if (a.oaOdRate() == null) {
            if (a.oaOdDecisionRef() != null || a.oaOdDecisionDocId() != null) {
                throw ApiException.unprocessable("oa-od-decision-without-rate",
                        "A BHTNLĐ-BNN decision is only recorded together with the reduced rate it grants");
            }
        } else {
            // Sanity bound only (same as the DDL check). That the rate is one the legal pack allows [verify-legal]
            // is checked once the Legal Parameter Service exists (TECH:lps).
            if (a.oaOdRate().signum() < 0 || a.oaOdRate().compareTo(new BigDecimal("0.05")) >= 0) {
                throw ApiException.unprocessable("invalid-oa-od-rate", "BHTNLĐ-BNN rate must be in [0, 0.05)");
            }
            if (a.oaOdDecisionRef() == null || a.oaOdDecisionRef().isBlank() || a.oaOdDecisionDocId() == null) {
                throw ApiException.unprocessable("oa-od-evidence-required",
                        "A reduced BHTNLĐ-BNN rate needs the decision number / issuing body and the evidence file");
            }
            if (!jdbc.sql("""
                            select exists (select 1 from core.document
                                            where tenant_id = :t and id = :id and status = 'ACTIVE')""")
                    .param("t", tenant).param("id", a.oaOdDecisionDocId()).query(Boolean.class).single()) {
                throw ApiException.unprocessable("unknown-document",
                        "Evidence document " + a.oaOdDecisionDocId() + " not found");
            }
        }
        return new Attributes(a.legalNameVi(), a.legalNameEn(), a.shortName(), taxCode.value(), a.siUnitCode(),
                a.businessRegNo(), a.businessRegDate(), a.businessRegIssuer(), a.phone(), a.email(),
                a.adminUnitCode(), a.addressLine(), a.legalRepName(), a.legalRepTitle(),
                a.fiscalYearStartMonth() == null ? 1 : a.fiscalYearStartMonth(), a.taxAuthorityCode(),
                a.pitDeclarationFrequency() == null ? PitDeclarationFrequency.MONTHLY : a.pitDeclarationFrequency(),
                a.oaOdRate(), a.oaOdDecisionRef(), a.oaOdDecisionDocId());
    }

    /** PIT declaration frequency is fixed per tax year: it may only change on 1 January (US-CORE-004 AC). */
    private void assertOnePitFrequencyPerTaxYear(UUID tenant, UUID id) {
        List<LocalDate> violations = jdbc.sql("""
                        select valid_from from (
                          select valid_from, pit_declaration_frequency,
                                 lag(pit_declaration_frequency) over (order by valid_from) as previous
                            from core.legal_entity_version where tenant_id = :t and legal_entity_id = :id) x
                         where previous is not null and previous <> pit_declaration_frequency""")
                .param("t", tenant).param("id", id)
                .query((rs, n) -> rs.getObject(1, LocalDate.class)).list().stream()
                .filter(d -> !MonthDay.from(d).equals(TAX_YEAR_START)).toList();
        if (!violations.isEmpty()) {
            throw ApiException.unprocessable("pit-frequency-mid-year",
                    "The PIT declaration frequency can only change from 1 January of a tax year; it changes on "
                            + violations.getFirst(), Map.of("dates", violations.stream().map(LocalDate::toString).toList()));
        }
    }

    private void insertVersion(UUID tenant, UUID id, LocalDate from, LocalDate to, UUID copyFrom, Attributes a) {
        jdbc.sql("""
                        insert into core.legal_entity_version (tenant_id, id, legal_entity_id, valid_from, valid_to,
                               legal_name_vi, legal_name_en, short_name, tax_code, si_unit_code, business_reg_no,
                               business_reg_date, business_reg_issuer, phone, email, admin_unit_code, address_line,
                               legal_rep_name, legal_rep_title, fiscal_year_start_month, tax_authority_code,
                               pit_declaration_frequency, oa_od_rate, oa_od_decision_ref, oa_od_decision_doc_id,
                               einvoice_identity, created_by, updated_by)
                        values (:t, :vid, :id, :from, :to, :nameVi, :nameEn, :short, :tax, :si, :reg, :regDate,
                                :regIssuer, :phone, :email, :au, :addr, :rep, :repTitle, :fy, :taxAuth, :pit, :oaOd,
                                :oaOdRef, :oaOdDoc,
                                (select einvoice_identity from core.legal_entity_version
                                  where tenant_id = :t and id = cast(:copyFrom as uuid)),
                                :actor, :actor)""")
                .param("vid", Ids.newId()).param("id", id).param("from", from).param("to", to)
                .param("copyFrom", copyFrom)
                .params(attributeParams(tenant, a))
                .update();
    }

    private void correctVersion(UUID tenant, UUID versionId, Attributes a) {
        jdbc.sql("""
                        update core.legal_entity_version
                           set legal_name_vi = :nameVi, legal_name_en = :nameEn, short_name = :short, tax_code = :tax,
                               si_unit_code = :si, business_reg_no = :reg, business_reg_date = :regDate,
                               business_reg_issuer = :regIssuer, phone = :phone, email = :email, admin_unit_code = :au,
                               address_line = :addr, legal_rep_name = :rep, legal_rep_title = :repTitle,
                               fiscal_year_start_month = :fy, tax_authority_code = :taxAuth,
                               pit_declaration_frequency = :pit, oa_od_rate = :oaOd, oa_od_decision_ref = :oaOdRef,
                               oa_od_decision_doc_id = :oaOdDoc, updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :vid""")
                .param("vid", versionId)
                .params(attributeParams(tenant, a))
                .update();
    }

    private static Map<String, Object> attributeParams(UUID tenant, Attributes a) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", tenant);
        p.put("nameVi", a.legalNameVi());
        p.put("nameEn", a.legalNameEn());
        p.put("short", a.shortName());
        p.put("tax", a.taxCode());
        p.put("si", a.siUnitCode());
        p.put("reg", a.businessRegNo());
        p.put("regDate", a.businessRegDate());
        p.put("regIssuer", a.businessRegIssuer());
        p.put("phone", a.phone());
        p.put("email", a.email());
        p.put("au", a.adminUnitCode());
        p.put("addr", a.addressLine());
        p.put("rep", a.legalRepName());
        p.put("repTitle", a.legalRepTitle());
        p.put("fy", a.fiscalYearStartMonth());
        p.put("taxAuth", a.taxAuthorityCode());
        p.put("pit", a.pitDeclarationFrequency().name());
        p.put("oaOd", a.oaOdRate());
        p.put("oaOdRef", a.oaOdDecisionRef());
        p.put("oaOdDoc", a.oaOdDecisionDocId());
        p.put("actor", TenantContext.current().userAccountId());
        return p;
    }

    private void assertCan(String permission, UUID id, LocalDate date) {
        // Out of scope looks the same as missing: no existence oracle across scopes.
        if (!authz.can(permission, new AuthorizationTarget.LegalEntity(id), date)) {
            throw ApiException.notFound("Legal entity", id);
        }
    }

    private void lockTenantLegalEntities(UUID tenant) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('core.legal_entity:' || cast(:t as text), 0))")
                .param("t", tenant).query((rs, n) -> 1).single();
    }

    private static ApiException limitReached(long limit, long used) {
        return new ApiException(HttpStatus.CONFLICT, "legal-entity-limit-reached",
                "Your subscription plan allows " + limit + " legal entities and " + used
                        + " already exist; upgrade the plan or dissolve one first",
                Map.of("limit", limit, "used", used));
    }

    private long countCounted(UUID tenant) {
        return jdbc.sql("select count(*) from core.legal_entity where tenant_id = :t and status <> 'DISSOLVED'")
                .param("t", tenant).query(Long.class).single();
    }

    private Optional<LegalEntityView> find(UUID id, LocalDate date) {
        return jdbc.sql(SELECT + " where le.tenant_id = :t and le.id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", id).param("d", date)
                .query((rs, n) -> map(rs)).optional();
    }

    /**
     * Anchor plus the version valid on {@code :d}; when none covers the date (not yet effective, or closed),
     * the latest version is shown so the entity stays visible in the list.
     */
    private static final String SELECT = """
            select le.id as le_id, le.code as le_code, le.status as le_status, le.version as le_version, v.*
              from core.legal_entity le
              left join lateral (
                    select * from core.legal_entity_version x
                     where x.tenant_id = le.tenant_id and x.legal_entity_id = le.id
                     order by (x.valid_during @> cast(:d as date)) desc, x.valid_from desc
                     limit 1) v on true""";

    private static LegalEntityView map(ResultSet rs) throws SQLException {
        return new LegalEntityView(rs.getObject("le_id", UUID.class), rs.getString("le_code"),
                LegalEntityStatus.valueOf(rs.getString("le_status")), rs.getInt("le_version"),
                rs.getObject("id", UUID.class) == null ? null : mapVersion(rs));
    }

    private static VersionView mapVersion(ResultSet rs) throws SQLException {
        return new VersionView(rs.getObject("id", UUID.class), rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class), rs.getString("legal_name_vi"),
                rs.getString("legal_name_en"), rs.getString("short_name"), rs.getString("tax_code"),
                rs.getString("si_unit_code"), rs.getString("business_reg_no"),
                rs.getObject("business_reg_date", LocalDate.class), rs.getString("business_reg_issuer"),
                rs.getString("phone"), rs.getString("email"), rs.getString("admin_unit_code"),
                rs.getString("address_line"), rs.getString("legal_rep_name"), rs.getString("legal_rep_title"),
                rs.getInt("fiscal_year_start_month"), rs.getString("tax_authority_code"),
                PitDeclarationFrequency.valueOf(rs.getString("pit_declaration_frequency")),
                rs.getBigDecimal("oa_od_rate"), rs.getString("oa_od_decision_ref"),
                rs.getObject("oa_od_decision_doc_id", UUID.class));
    }
}
