package vn.yourhrm.core.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.CoreEvents.MasterDataReleased;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Loads a master-data release published by the SaaS back-office into Core's global store (BR-CORE-080/082,
 * US-CORE-084): countries, currencies, administrative units and their old→new mapping, banks and branches, code
 * lists and global code values.
 *
 * <p>The application roles only read these global tables, so the release job calls this with a connection of the
 * platform role ({@code yhrm_platform}). One release is loaded all or nothing in one transaction together with its
 * {@code core.master_data_release} history row; loading a release number again with the same content is a no-op, with
 * different content an error. {@code core.MasterDataReleased} is published after commit.
 */
@Component
public class MasterDataReleaseLoader {

    private static final List<String> LEVEL_ORDER = List.of("PROVINCE", "DISTRICT", "COMMUNE");

    private final CoreJson json;
    private final ApplicationEventPublisher events;

    public MasterDataReleaseLoader(CoreJson json, ApplicationEventPublisher events) {
        this.json = json;
        this.events = events;
    }

    public record Release(long releaseNo, String title, LocalDate effectiveDate, Instant publishedAt,
            List<Country> countries, List<Currency> currencies, List<AdminUnit> adminUnits,
            List<AdminUnitMapping> adminUnitMappings, List<Bank> banks, List<BankBranch> bankBranches,
            List<CodeList> codeLists, List<CodeValue> codeValues) {

        public Release {
            countries = countries == null ? List.of() : List.copyOf(countries);
            currencies = currencies == null ? List.of() : List.copyOf(currencies);
            adminUnits = adminUnits == null ? List.of() : List.copyOf(adminUnits);
            adminUnitMappings = adminUnitMappings == null ? List.of() : List.copyOf(adminUnitMappings);
            banks = banks == null ? List.of() : List.copyOf(banks);
            bankBranches = bankBranches == null ? List.of() : List.copyOf(bankBranches);
            codeLists = codeLists == null ? List.of() : List.copyOf(codeLists);
            codeValues = codeValues == null ? List.of() : List.copyOf(codeValues);
        }
    }

    public record Country(String code, String iso3, String nameVi, String nameEn, String nationalityVi,
            String nationalityEn, boolean active) {
    }

    public record Currency(String code, String nameVi, String nameEn, int minorUnits) {
    }

    public record AdminUnit(String code, String level, String nameVi, String nameEn, String parentCode,
            LocalDate validFrom, LocalDate validTo, String replacedByCode, String sourceDecision) {
    }

    public record AdminUnitMapping(String oldCode, String newCode, LocalDate effectiveDate, String mappingType,
            String shareNote) {
    }

    public record Bank(String bankCode, String bin, String swiftCode, String shortName, String nameVi, String nameEn,
            boolean active) {
    }

    public record BankBranch(String bankCode, String branchCode, String nameVi, String provinceCode, boolean active) {
    }

    public record CodeList(String listCode, String nameVi, String nameEn, boolean tenantExtensible, boolean statutory,
            String ownerModule) {
    }

    public record CodeValue(String listCode, String valueCode, String labelVi, String labelEn, int sortOrder,
            Map<String, Object> attributes, boolean active, LocalDate validFrom, LocalDate validTo) {
    }

    public enum Outcome { LOADED, ALREADY_LOADED }

    /** Loads {@code release} through the platform role's {@code dataSource}. */
    public Outcome load(DataSource platformDataSource, Release release) {
        JdbcClient jdbc = JdbcClient.create(platformDataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(platformDataSource));
        String sha = sha256(json.write(release));
        Outcome outcome = tx.execute(s -> {
            Optional<String> loaded = jdbc.sql("""
                            select content_sha256 from core.master_data_release where release_no = :n for update""")
                    .param("n", release.releaseNo()).query(String.class).optional();
            if (loaded.isPresent()) {
                if (!loaded.get().equals(sha)) {
                    throw ApiException.conflict("release-content-differs", "Release " + release.releaseNo()
                            + " was already loaded with different content; publish a new release number");
                }
                return Outcome.ALREADY_LOADED;
            }
            Map<String, Object> counts = new LinkedHashMap<>();
            loadAll(jdbc, release, counts);
            jdbc.sql("""
                            insert into core.master_data_release (release_no, title, datasets, item_counts, content_sha256,
                                   effective_date, published_at)
                            values (:n, :title, cast(:ds as text[]), cast(:counts as jsonb), :sha, :eff, :pub)""")
                    .param("n", release.releaseNo()).param("title", release.title())
                    .param("ds", counts.keySet().toArray(String[]::new)).param("counts", json.write(counts))
                    .param("sha", sha).param("eff", release.effectiveDate())
                    .param("pub", Timestamp.from(release.publishedAt()))
                    .update();
            return Outcome.LOADED;
        });
        if (outcome == Outcome.LOADED) {
            events.publishEvent(new MasterDataReleased(release.releaseNo(), datasets(release), release.effectiveDate()));
        }
        return outcome;
    }

    private void loadAll(JdbcClient jdbc, Release r, Map<String, Object> counts) {
        count(counts, "COUNTRY", r.countries().size());
        for (Country c : r.countries()) {
            jdbc.sql("""
                            insert into core.country (country_code, iso3, name_vi, name_en, nationality_vi, nationality_en,
                                   is_active) values (:c, :iso3, :vi, :en, :nvi, :nen, :a)
                            on conflict (country_code) do update set iso3 = excluded.iso3, name_vi = excluded.name_vi,
                               name_en = excluded.name_en, nationality_vi = excluded.nationality_vi,
                               nationality_en = excluded.nationality_en, is_active = excluded.is_active""")
                    .param("c", c.code()).param("iso3", c.iso3()).param("vi", c.nameVi()).param("en", c.nameEn())
                    .param("nvi", c.nationalityVi()).param("nen", c.nationalityEn()).param("a", c.active()).update();
        }
        count(counts, "CURRENCY", r.currencies().size());
        for (Currency c : r.currencies()) {
            jdbc.sql("""
                            insert into core.currency (currency_code, name_vi, name_en, minor_units) values (:c, :vi, :en, :m)
                            on conflict (currency_code) do update set name_vi = excluded.name_vi, name_en = excluded.name_en,
                               minor_units = excluded.minor_units""")
                    .param("c", c.code()).param("vi", c.nameVi()).param("en", c.nameEn()).param("m", c.minorUnits())
                    .update();
        }
        // parents before children; replaced_by is set in a second pass (the successor may come later)
        count(counts, "ADMIN_UNIT", r.adminUnits().size());
        List<AdminUnit> units = new ArrayList<>(r.adminUnits());
        units.sort(Comparator.comparingInt(u -> LEVEL_ORDER.indexOf(u.level())));
        for (AdminUnit u : units) {
            jdbc.sql("""
                            insert into core.admin_unit (admin_unit_code, level, name_vi, name_en, parent_code, valid_from,
                                   valid_to, source_decision)
                            values (:c, :l, :vi, :en, :p, :from, :to, :src)
                            on conflict (admin_unit_code) do update set level = excluded.level, name_vi = excluded.name_vi,
                               name_en = excluded.name_en, parent_code = excluded.parent_code,
                               valid_from = excluded.valid_from, valid_to = excluded.valid_to,
                               source_decision = excluded.source_decision""")
                    .param("c", u.code()).param("l", u.level()).param("vi", u.nameVi()).param("en", u.nameEn())
                    .param("p", u.parentCode()).param("from", u.validFrom()).param("to", u.validTo())
                    .param("src", u.sourceDecision())
                    .update();
        }
        for (AdminUnit u : units) {
            if (u.replacedByCode() != null) {
                jdbc.sql("update core.admin_unit set replaced_by_code = :r where admin_unit_code = :c")
                        .param("r", u.replacedByCode()).param("c", u.code()).update();
            }
        }
        count(counts, "ADMIN_UNIT_MAPPING", r.adminUnitMappings().size());
        for (AdminUnitMapping m : r.adminUnitMappings()) {
            jdbc.sql("""
                            insert into core.admin_unit_mapping (old_code, new_code, effective_date, mapping_type, share_note)
                            values (:o, :n, :d, :t, :note)
                            on conflict (old_code, new_code, effective_date) do update
                               set mapping_type = excluded.mapping_type, share_note = excluded.share_note""")
                    .param("o", m.oldCode()).param("n", m.newCode()).param("d", m.effectiveDate())
                    .param("t", m.mappingType()).param("note", m.shareNote())
                    .update();
        }
        count(counts, "BANK", r.banks().size());
        for (Bank b : r.banks()) {
            jdbc.sql("""
                            insert into core.bank (bank_code, bin, swift_code, short_name, name_vi, name_en, is_active)
                            values (:c, :bin, :swift, :short, :vi, :en, :a)
                            on conflict (bank_code) do update set bin = excluded.bin, swift_code = excluded.swift_code,
                               short_name = excluded.short_name, name_vi = excluded.name_vi, name_en = excluded.name_en,
                               is_active = excluded.is_active""")
                    .param("c", b.bankCode()).param("bin", b.bin()).param("swift", b.swiftCode())
                    .param("short", b.shortName()).param("vi", b.nameVi()).param("en", b.nameEn())
                    .param("a", b.active()).update();
        }
        count(counts, "BANK_BRANCH", r.bankBranches().size());
        for (BankBranch b : r.bankBranches()) {
            jdbc.sql("""
                            insert into core.bank_branch (bank_code, branch_code, name_vi, province_code, is_active)
                            values (:b, :c, :vi, :p, :a)
                            on conflict (bank_code, branch_code) do update set name_vi = excluded.name_vi,
                               province_code = excluded.province_code, is_active = excluded.is_active""")
                    .param("b", b.bankCode()).param("c", b.branchCode()).param("vi", b.nameVi())
                    .param("p", b.provinceCode()).param("a", b.active()).update();
        }
        count(counts, "CODE_LIST", r.codeLists().size());
        for (CodeList l : r.codeLists()) {
            jdbc.sql("""
                            insert into core.code_list (list_code, name_vi, name_en, is_tenant_extensible, is_statutory,
                                   owner_module) values (:c, :vi, :en, :ext, :stat, :m)
                            on conflict (list_code) do update set name_vi = excluded.name_vi, name_en = excluded.name_en,
                               is_tenant_extensible = excluded.is_tenant_extensible, is_statutory = excluded.is_statutory,
                               owner_module = excluded.owner_module""")
                    .param("c", l.listCode()).param("vi", l.nameVi()).param("en", l.nameEn())
                    .param("ext", l.tenantExtensible()).param("stat", l.statutory()).param("m", l.ownerModule())
                    .update();
        }
        count(counts, "CODE_VALUE", r.codeValues().size());
        for (CodeValue v : r.codeValues()) {
            jdbc.sql("""
                            insert into core.code_value (tenant_id, list_code, value_code, label_vi, label_en, sort_order,
                                   attributes, is_active, valid_from, valid_to)
                            values (sys.global_tenant_id(), :l, :v, :vi, :en, :s, cast(:attr as jsonb), :a,
                                    coalesce(cast(:from as date), date '1900-01-01'), :to)
                            on conflict (tenant_id, list_code, value_code) do update
                               set label_vi = excluded.label_vi, label_en = excluded.label_en,
                                   sort_order = excluded.sort_order, attributes = excluded.attributes,
                                   is_active = excluded.is_active, valid_from = excluded.valid_from,
                                   valid_to = excluded.valid_to""")
                    .param("l", v.listCode()).param("v", v.valueCode()).param("vi", v.labelVi())
                    .param("en", v.labelEn()).param("s", v.sortOrder())
                    .param("attr", json.write(v.attributes() == null ? Map.of() : v.attributes()))
                    .param("a", v.active()).param("from", v.validFrom()).param("to", v.validTo())
                    .update();
        }
    }

    private static void count(Map<String, Object> counts, String dataset, int n) {
        if (n > 0) {
            counts.put(dataset, n);
        }
    }

    private static List<String> datasets(Release r) {
        Map<String, Object> counts = new LinkedHashMap<>();
        count(counts, "COUNTRY", r.countries().size());
        count(counts, "CURRENCY", r.currencies().size());
        count(counts, "ADMIN_UNIT", r.adminUnits().size());
        count(counts, "ADMIN_UNIT_MAPPING", r.adminUnitMappings().size());
        count(counts, "BANK", r.banks().size());
        count(counts, "BANK_BRANCH", r.bankBranches().size());
        count(counts, "CODE_LIST", r.codeLists().size());
        count(counts, "CODE_VALUE", r.codeValues().size());
        return List.copyOf(counts.keySet());
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
