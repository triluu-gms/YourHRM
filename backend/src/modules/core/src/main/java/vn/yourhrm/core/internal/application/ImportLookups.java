package vn.yourhrm.core.internal.application;

import java.sql.Date;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Code-or-name lookups of import columns (BR-CORE-112, US-CORE-115): an input matches a code exactly (case-insensitive,
 * or the code part of a template dropdown entry "CODE - Name"), else a name accent-insensitively ({@code đ = d}); a
 * name shared by several entries is reported as ambiguous. Runs in the caller's transaction (RLS).
 */
@Component
public class ImportLookups {

    /** One candidate: {@code value} is the code (code lists) or the record id. */
    public record Entry(String code, List<String> names, Object value) {
    }

    /** {@code value} when exactly one entry matched; {@code ambiguous} lists the codes of several name matches. */
    public record Match(Object value, List<String> ambiguous) {

        public boolean found() {
            return value != null;
        }
    }

    private final JdbcClient jdbc;

    public ImportLookups(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every entry of {@code lookup} valid on {@code asOf}. */
    public List<Entry> entries(String lookup, LocalDate asOf) {
        UUID t = TenantContext.current().tenantId();
        Date d = Date.valueOf(asOf);
        if (lookup.startsWith("CODE_LIST:")) {
            return jdbc.sql("""
                    select value_code, label_vi, label_en from core.v_code_value_effective
                     where list_code = :l and is_active and valid_from <= :d and (valid_to is null or valid_to > :d)
                     order by sort_order, value_code""")
                    .param("l", lookup.substring("CODE_LIST:".length())).param("d", d)
                    .query((rs, n) -> new Entry(rs.getString(1), names(rs.getString(2), rs.getString(3)), rs.getString(1)))
                    .list();
        }
        String sql = switch (lookup) {
            case "LEGAL_ENTITY" -> """
                    select e.code, v.legal_name_vi, v.short_name, e.id from core.legal_entity e
                      join core.legal_entity_version v on v.tenant_id = e.tenant_id and v.legal_entity_id = e.id
                           and v.valid_during @> cast(:d as date)
                     where e.tenant_id = :t and e.status <> 'DISSOLVED' order by e.code""";
            case "LOCATION" -> """
                    select l.code, v.name, null, l.id from core.location l
                      join core.location_version v on v.tenant_id = l.tenant_id and v.location_id = l.id
                           and v.valid_during @> cast(:d as date)
                     where l.tenant_id = :t order by l.code""";
            case "ORG_UNIT" -> """
                    select u.code, v.name_vi, v.name_en, u.id from core.org_unit u
                      join core.org_unit_version v on v.tenant_id = u.tenant_id and v.org_unit_id = u.id
                           and v.valid_during @> cast(:d as date) and v.status = 'ACTIVE'
                     where u.tenant_id = :t order by u.code""";
            case "ORG_UNIT_TYPE" -> """
                    select code, name_vi, name_en, id from core.org_unit_type
                     where tenant_id = :t and is_active and cast(:d as date) is not null order by sort_order, code""";
            default -> throw new IllegalArgumentException("Unknown lookup " + lookup);
        };
        return jdbc.sql(sql).param("t", t).param("d", d)
                .query((rs, n) -> new Entry(rs.getString(1), names(rs.getString(2), rs.getString(3)),
                        rs.getObject(4, UUID.class)))
                .list();
    }

    /** Matches {@code input} against {@code entries} (see the class comment). */
    public static Match match(List<Entry> entries, String input) {
        String in = input.strip();
        String codePart = in.contains(" - ") ? in.substring(0, in.indexOf(" - ")).strip() : in;
        for (Entry e : entries) {
            if (e.code().equalsIgnoreCase(in) || e.code().equalsIgnoreCase(codePart)) {
                return new Match(e.value(), List.of());
            }
        }
        String key = searchKey(in);
        List<Entry> byName = new ArrayList<>();
        for (Entry e : entries) {
            if (e.names().stream().anyMatch(n -> searchKey(n).equals(key))) {
                byName.add(e);
            }
        }
        if (byName.size() == 1) {
            return new Match(byName.getFirst().value(), List.of());
        }
        return new Match(null, byName.stream().map(Entry::code).toList());
    }

    /** Java twin of {@code sys.search_key}: lower case, accents removed, đ → d. */
    static String searchKey(String s) {
        String noMarks = Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return noMarks.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static List<String> names(String a, String b) {
        List<String> out = new ArrayList<>();
        if (a != null && !a.isBlank()) {
            out.add(a);
        }
        if (b != null && !b.isBlank()) {
            out.add(b);
        }
        return out;
    }
}
