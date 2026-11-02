package vn.yourhrm.core.internal.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.MasterData;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Global master data, read side (BR-CORE-080..082, US-CORE-080..083): administrative units (34 provinces and their
 * communes from 2025-07-01, plus the pre-2025 63-province / district / commune model with validity dates), the
 * old→new resolver, code lists (global values with tenant overrides), countries, currencies, banks and branches.
 * Every search is accent-insensitive ({@code sys.search_key}: lower + unaccent + đ→d, NFR-CORE-011).
 */
@Service
public class MasterDataService implements MasterData {

    private static final int MAX_MAPPING_HOPS = 10;

    private final JdbcClient jdbc;
    private final CoreJson json;
    private final BusinessClock clock;

    public MasterDataService(JdbcClient jdbc, CoreJson json, BusinessClock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public record CodeListView(String listCode, String nameVi, String nameEn, boolean tenantExtensible,
            boolean statutory, String ownerModule) {
    }

    public record CodeValueView(String valueCode, String labelVi, String labelEn, int sortOrder,
            Map<String, Object> attributes, boolean active, LocalDate validFrom, LocalDate validTo, boolean tenantValue) {
    }

    public record CountryView(String code, String iso3, String nameVi, String nameEn, String nationalityVi,
            String nationalityEn) {
    }

    public record CurrencyView(String code, String nameVi, String nameEn, int minorUnits) {
    }

    public record BankView(String bankCode, String bin, String swiftCode, String shortName, String nameVi,
            String nameEn, boolean active) {
    }

    public record BankBranchView(String bankCode, String branchCode, String nameVi, String provinceCode,
            boolean active) {
    }

    public record ReleaseView(long releaseNo, String title, List<String> datasets, Map<String, Object> itemCounts,
            LocalDate effectiveDate, Instant publishedAt, Instant loadedAt) {
    }

    /** An address unit with its ancestors, written in the given form. */
    public record FormattedAddress(String code, String form, String text, List<AdminUnit> path, Resolution resolution) {
    }

    // ------------------------------------------------------------------------------------ administrative units

    @Override
    @Transactional(readOnly = true)
    public Optional<AdminUnit> adminUnit(String code) {
        return jdbc.sql(UNIT + " where admin_unit_code = :c").param("c", code).query((rs, n) -> unit(rs)).optional();
    }

    /** Units of a level (PROVINCE by default) or the children of {@code parentCode}, valid on {@code asOf}. */
    @Transactional(readOnly = true)
    public List<AdminUnit> adminUnits(String parentCode, String level, LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        return jdbc.sql(UNIT + """
                         where valid_from <= :d and (valid_to is null or valid_to > :d)
                           and (cast(:p as text) is null and level = coalesce(cast(:l as text), 'PROVINCE')
                                or parent_code = :p and (cast(:l as text) is null or level = :l))
                         order by name_vi""")
                .param("d", date).param("p", parentCode).param("l", level)
                .query((rs, n) -> unit(rs)).list();
    }

    /** Accent-insensitive search by name or code, valid on {@code asOf} (all dates when {@code asOf} is null). */
    @Transactional(readOnly = true)
    public List<AdminUnit> searchAdminUnits(String query, String level, LocalDate asOf, int limit) {
        return jdbc.sql(UNIT + """
                         where (sys.search_key(name_vi) like '%' || sys.search_key(:q) || '%' or admin_unit_code = :q)
                           and (cast(:l as text) is null or level = :l)
                           and (cast(:d as date) is null or (valid_from <= :d and (valid_to is null or valid_to > :d)))
                         order by level, name_vi limit :limit""")
                .param("q", query).param("l", level).param("d", asOf).param("limit", limit)
                .query((rs, n) -> unit(rs)).list();
    }

    /**
     * BR-CORE-081: follows the old→new mapping (merge, split, rename, recode; several hops if needed) from
     * {@code code} to the unit(s) valid on {@code asOf}. A split yields several candidates and the caller must choose.
     */
    @Override
    @Transactional(readOnly = true)
    public Resolution resolveAdminUnit(String code, LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        Optional<AdminUnit> start = adminUnit(code);
        if (start.isEmpty()) {
            return new Resolution(code, "UNKNOWN", List.of(), "No administrative unit has this code");
        }
        if (valid(start.get(), date)) {
            return new Resolution(code, "CURRENT", List.of(start.get()), null);
        }
        Set<String> types = new LinkedHashSet<>();
        Map<String, AdminUnit> found = new LinkedHashMap<>();
        Deque<String> frontier = new ArrayDeque<>(List.of(code));
        Set<String> seen = new LinkedHashSet<>(List.of(code));
        for (int hop = 0; hop < MAX_MAPPING_HOPS && !frontier.isEmpty(); hop++) {
            Deque<String> next = new ArrayDeque<>();
            for (String c : frontier) {
                record Edge(String to, String type) {
                }
                List<Edge> edges = jdbc.sql("""
                                select new_code, mapping_type from core.admin_unit_mapping
                                 where old_code = :c and effective_date <= :d order by new_code""")
                        .param("c", c).param("d", date).query((rs, n) -> new Edge(rs.getString(1), rs.getString(2)))
                        .list();
                if (edges.isEmpty()) {
                    adminUnit(c).map(AdminUnit::code).flatMap(x -> replacedBy(x)).ifPresent(r -> {
                        types.add("REPLACED_BY");
                        edgesTo(r, date, found, next, seen);
                    });
                }
                for (Edge e : edges) {
                    types.add(e.type());
                    edgesTo(e.to(), date, found, next, seen);
                }
            }
            frontier = next;
        }
        List<AdminUnit> candidates = List.copyOf(found.values());
        String status = candidates.isEmpty() ? "UNKNOWN" : candidates.size() == 1 ? "MAPPED" : "AMBIGUOUS";
        String note = candidates.isEmpty() ? "Unit ended without a successor valid on " + date
                : "Via " + String.join(", ", types) + (candidates.size() > 1 ? "; choose one" : "");
        return new Resolution(code, status, candidates, note);
    }

    private void edgesTo(String to, LocalDate date, Map<String, AdminUnit> found, Deque<String> next, Set<String> seen) {
        if (!seen.add(to)) {
            return;
        }
        adminUnit(to).ifPresent(u -> {
            if (valid(u, date)) {
                found.put(u.code(), u);
            } else {
                next.add(u.code());
            }
        });
    }

    private Optional<String> replacedBy(String code) {
        return jdbc.sql("select replaced_by_code from core.admin_unit where admin_unit_code = :c and replaced_by_code is not null")
                .param("c", code).query(String.class).optional();
    }

    /**
     * The address line of a unit (commune, district, province), in the OLD form (the unit as recorded) or the NEW form
     * (its successor valid on {@code asOf}; empty text when ambiguous). Birthplaces keep the old form (US-CORE-081).
     */
    @Transactional(readOnly = true)
    public FormattedAddress format(String code, String form, LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        if ("OLD".equals(form)) {
            List<AdminUnit> path = path(code);
            if (path.isEmpty()) {
                throw ApiException.notFound("Administrative unit", code);
            }
            return new FormattedAddress(code, form, text(path), path, null);
        }
        Resolution r = resolveAdminUnit(code, date);
        if (r.candidates().size() != 1) {
            return new FormattedAddress(code, "NEW", null, List.of(), r);
        }
        List<AdminUnit> path = path(r.candidates().getFirst().code());
        return new FormattedAddress(code, "NEW", text(path), path, r);
    }

    /** The unit and its ancestors, the unit first. */
    private List<AdminUnit> path(String code) {
        List<AdminUnit> out = new ArrayList<>();
        Optional<AdminUnit> u = adminUnit(code);
        int guard = 0;
        while (u.isPresent() && guard++ < 5) {
            out.add(u.get());
            u = u.get().parentCode() == null ? Optional.empty() : adminUnit(u.get().parentCode());
        }
        return out;
    }

    private static String text(List<AdminUnit> path) {
        return String.join(", ", path.stream().map(AdminUnit::nameVi).toList());
    }

    private static boolean valid(AdminUnit u, LocalDate date) {
        return !u.validFrom().isAfter(date) && (u.validTo() == null || u.validTo().isAfter(date));
    }

    // ------------------------------------------------------------------------------------ code lists

    @Transactional(readOnly = true)
    public List<CodeListView> codeLists() {
        return jdbc.sql("""
                        select list_code, name_vi, name_en, is_tenant_extensible, is_statutory, owner_module
                          from core.code_list order by list_code""")
                .query((rs, n) -> new CodeListView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getString(6)))
                .list();
    }

    /** Effective values (a tenant row shadows the global one) valid on {@code asOf}, sorted. */
    @Transactional(readOnly = true)
    public List<CodeValueView> codeValues(String listCode, LocalDate asOf, String query, boolean includeInactive) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        if (!jdbc.sql("select exists (select 1 from core.code_list where list_code = :l)").param("l", listCode)
                .query(Boolean.class).single()) {
            throw ApiException.notFound("Code list", listCode);
        }
        return jdbc.sql("""
                        select value_code, label_vi, label_en, sort_order, attributes::text, is_active, valid_from,
                               valid_to, tenant_id <> sys.global_tenant_id()
                          from core.v_code_value_effective
                         where list_code = :l and valid_from <= :d and (valid_to is null or valid_to > :d)
                           and (:all or is_active)
                           and (cast(:q as text) is null or sys.search_key(label_vi) like '%' || sys.search_key(:q) || '%'
                                or sys.search_key(label_en) like '%' || sys.search_key(:q) || '%' or value_code = :q)
                         order by sort_order, label_vi""")
                .param("l", listCode).param("d", date).param("all", includeInactive).param("q", blank(query))
                .query((rs, n) -> new CodeValueView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        json.map(rs.getString(5)), rs.getBoolean(6), rs.getObject(7, LocalDate.class),
                        rs.getObject(8, LocalDate.class), rs.getBoolean(9)))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isValidCode(String listCode, String valueCode, LocalDate asOf) {
        return jdbc.sql("""
                        select exists (select 1 from core.v_code_value_effective
                                        where list_code = :l and value_code = :v and is_active
                                          and valid_from <= :d and (valid_to is null or valid_to > :d))""")
                .param("l", listCode).param("v", valueCode).param("d", asOf == null ? clock.today() : asOf)
                .query(Boolean.class).single();
    }

    // ------------------------------------------------------------------------------------ countries, currencies, banks

    @Transactional(readOnly = true)
    public List<CountryView> countries(String query) {
        return jdbc.sql("""
                        select country_code, iso3, name_vi, name_en, nationality_vi, nationality_en from core.country
                         where is_active and (cast(:q as text) is null
                               or sys.search_key(name_vi) like '%' || sys.search_key(:q) || '%'
                               or sys.search_key(name_en) like '%' || sys.search_key(:q) || '%'
                               or country_code = upper(:q) or iso3 = upper(:q))
                         order by name_vi""")
                .param("q", blank(query))
                .query((rs, n) -> new CountryView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<CurrencyView> currencies() {
        return jdbc.sql("select currency_code, name_vi, name_en, minor_units from core.currency order by currency_code")
                .query((rs, n) -> new CurrencyView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)))
                .list();
    }

    /** Banks by code or name, accent-insensitive; inactive banks are hidden from new entries (US-CORE-083). */
    @Transactional(readOnly = true)
    public List<BankView> banks(String query, boolean includeInactive) {
        return jdbc.sql("""
                        select bank_code, bin, swift_code, short_name, name_vi, name_en, is_active from core.bank
                         where (:all or is_active) and (cast(:q as text) is null
                               or sys.search_key(name_vi) like '%' || sys.search_key(:q) || '%'
                               or sys.search_key(short_name) like '%' || sys.search_key(:q) || '%'
                               or bank_code = :q or bin = :q or upper(swift_code) = upper(:q))
                         order by short_name""")
                .param("all", includeInactive).param("q", blank(query))
                .query((rs, n) -> new BankView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<BankBranchView> bankBranches(String bankCode, String query, boolean includeInactive) {
        return jdbc.sql("""
                        select bank_code, branch_code, name_vi, province_code, is_active from core.bank_branch
                         where bank_code = :b and (:all or is_active) and (cast(:q as text) is null
                               or sys.search_key(name_vi) like '%' || sys.search_key(:q) || '%' or branch_code = :q)
                         order by name_vi""")
                .param("b", bankCode).param("all", includeInactive).param("q", blank(query))
                .query((rs, n) -> new BankBranchView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBoolean(5)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<ReleaseView> releases() {
        return jdbc.sql("""
                        select release_no, title, datasets, item_counts::text, effective_date, published_at, loaded_at
                          from core.master_data_release order by release_no desc""")
                .query((rs, n) -> new ReleaseView(rs.getLong(1), rs.getString(2),
                        List.of((String[]) rs.getArray(3).getArray()), json.map(rs.getString(4)),
                        rs.getObject(5, LocalDate.class), rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7).toInstant()))
                .list();
    }

    // ------------------------------------------------------------------------------------ helpers

    private static final String UNIT = """
            select admin_unit_code, level, name_vi, name_en, parent_code, valid_from, valid_to from core.admin_unit
            """;

    private static AdminUnit unit(ResultSet rs) throws SQLException {
        return new AdminUnit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
