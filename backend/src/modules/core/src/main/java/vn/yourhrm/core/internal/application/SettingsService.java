package vn.yourhrm.core.internal.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Hierarchical typed tenant settings (BR-CORE-084, US-CORE-085, interface P-17). Resolution takes the most specific
 * value valid on the date: LOCATION, then LEGAL_ENTITY, then TENANT, then the definition's default. Values are
 * validated against the definition's type; unknown keys are refused. Non-dated settings keep one row per level
 * ({@code valid_from} 1900-01-01); dated ones split the covering row like any effective-dated record. Every change is
 * in the row audit of {@code core.setting_value}.
 */
@Service
public class SettingsService implements Settings {

    private static final LocalDate OPEN = LocalDate.of(1900, 1, 1);

    private final JdbcClient jdbc;
    private final CoreJson json;
    private final BusinessClock clock;

    public SettingsService(JdbcClient jdbc, CoreJson json, BusinessClock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** A registered setting. */
    record Definition(String key, String ownerModule, String valueType, List<String> allowedValues, Object defaultValue,
            List<String> allowedLevels, boolean effectiveDated, boolean sensitive, String descriptionVi,
            String descriptionEn) {
    }

    public record SettingView(String key, String ownerModule, String valueType, List<String> allowedValues,
            Object defaultValue, List<String> allowedLevels, boolean effectiveDated, boolean sensitive,
            String descriptionVi, String descriptionEn, Object tenantValue, List<ValueView> values) {
    }

    public record ValueView(UUID id, String level, UUID legalEntityId, UUID locationId, Object value,
            LocalDate validFrom, LocalDate validTo, int version) {
    }

    public record Resolved(String key, Object value, String source, UUID valueId, LocalDate validFrom) {
    }

    // ------------------------------------------------------------------------------------ resolution (P-17)

    @Override
    @Transactional(readOnly = true)
    public Object value(String key, Scope scope, LocalDate asOf) {
        return resolve(key, scope, asOf).value();
    }

    // The typed accessors are overridden (not inherited as interface defaults) so that each call through the Spring
    // proxy runs in a transaction: a default method would call value() on the target itself, outside any
    // transaction, where RLS hides every tenant row and the definition's default would silently be returned.

    @Override
    @Transactional(readOnly = true)
    public boolean booleanValue(String key, Scope scope, LocalDate asOf) {
        return Boolean.TRUE.equals(value(key, scope, asOf));
    }

    @Override
    @Transactional(readOnly = true)
    public long longValue(String key, Scope scope, LocalDate asOf) {
        return ((Number) value(key, scope, asOf)).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    public String stringValue(String key, Scope scope, LocalDate asOf) {
        Object v = value(key, scope, asOf);
        return v == null ? null : String.valueOf(v);
    }

    @Transactional(readOnly = true)
    public Resolved resolve(String key, Scope scope, LocalDate asOf) {
        Definition d = definition(key);
        LocalDate date = asOf == null ? clock.today() : asOf;
        Scope s = normalise(scope);
        record Row(UUID id, String level, String value, LocalDate from) {
        }
        Optional<Row> best = jdbc.sql("""
                        select id, scope_level, value::text, valid_from from core.setting_value
                         where tenant_id = :t and setting_key = :k and valid_during @> cast(:d as date)
                           and (scope_level = 'TENANT'
                                or scope_level = 'LEGAL_ENTITY' and legal_entity_id = :le
                                or scope_level = 'LOCATION' and location_id = :loc)
                         order by case scope_level when 'LOCATION' then 0 when 'LEGAL_ENTITY' then 1 else 2 end
                         limit 1""")
                .param("t", tenant()).param("k", key).param("d", date).param("le", s.legalEntityId())
                .param("loc", s.locationId())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class)))
                .optional();
        return best.map(r -> new Resolved(key, json.value(r.value()), r.level(), r.id(), r.from()))
                .orElse(new Resolved(key, d.defaultValue(), "DEFAULT", null, null));
    }

    // ------------------------------------------------------------------------------------ administration

    @Transactional(readOnly = true)
    public List<SettingView> list() {
        return definitions().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SettingView get(String key) {
        return view(definition(key));
    }

    /**
     * Sets a value at a level from {@code validFrom} (dated settings) or for good (non-dated settings, where
     * {@code validFrom} is ignored).
     */
    @Transactional
    public Resolved set(String key, String level, UUID legalEntityId, UUID locationId, Object value,
            LocalDate validFrom) {
        Definition d = definition(key);
        UUID tenant = tenant();
        if (!d.allowedLevels().contains(level)) {
            throw ApiException.unprocessable("level-not-allowed",
                    key + " can be set at " + d.allowedLevels() + ", not " + level);
        }
        Scope scope = checkScope(tenant, level, legalEntityId, locationId);
        String valueJson = json.write(validate(d, value));
        LocalDate from = d.effectiveDated() ? (validFrom == null ? clock.today() : validFrom) : OPEN;
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('core.setting:' || cast(:t as text) || :k, 0))")
                .param("t", tenant).param("k", key).query((rs, n) -> 1).single();
        record Covering(UUID id, LocalDate from, LocalDate to) {
        }
        Optional<Covering> covering = jdbc.sql("""
                        select id, valid_from, valid_to from core.setting_value
                         where tenant_id = :t and setting_key = :k and scope_level = :lvl
                           and legal_entity_id is not distinct from :le and location_id is not distinct from :loc
                           and valid_during @> cast(:d as date)""")
                .param("t", tenant).param("k", key).param("lvl", level).param("le", scope.legalEntityId())
                .param("loc", scope.locationId()).param("d", from)
                .query((rs, n) -> new Covering(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class)))
                .optional();
        UUID actor = TenantContext.current().userAccountId();
        if (covering.isPresent() && covering.get().from().equals(from)) {
            jdbc.sql("""
                            update core.setting_value set value = cast(:v as jsonb), updated_by = :a, version = version + 1
                             where tenant_id = :t and id = :id""")
                    .param("v", valueJson).param("a", actor).param("t", tenant).param("id", covering.get().id()).update();
        } else {
            LocalDate to = null;
            if (covering.isPresent()) {
                to = covering.get().to();
                jdbc.sql("update core.setting_value set valid_to = :d, updated_by = :a where tenant_id = :t and id = :id")
                        .param("d", from).param("a", actor).param("t", tenant).param("id", covering.get().id()).update();
            } else {
                // a later value of the same level may already exist: the new one ends where it starts
                to = jdbc.sql("""
                                select min(valid_from) from core.setting_value
                                 where tenant_id = :t and setting_key = :k and scope_level = :lvl
                                   and legal_entity_id is not distinct from :le and location_id is not distinct from :loc
                                   and valid_from > :d""")
                        .param("t", tenant).param("k", key).param("lvl", level).param("le", scope.legalEntityId())
                        .param("loc", scope.locationId()).param("d", from)
                        .query((rs, n) -> Optional.ofNullable(rs.getObject(1, LocalDate.class))).single().orElse(null);
            }
            jdbc.sql("""
                            insert into core.setting_value (tenant_id, id, setting_key, scope_level, legal_entity_id,
                                   location_id, value, valid_from, valid_to, created_by, updated_by)
                            values (:t, :id, :k, :lvl, :le, :loc, cast(:v as jsonb), :from, :to, :a, :a)""")
                    .param("t", tenant).param("id", Ids.newId()).param("k", key).param("lvl", level)
                    .param("le", scope.legalEntityId()).param("loc", scope.locationId()).param("v", valueJson)
                    .param("from", from).param("to", to).param("a", actor)
                    .update();
        }
        return resolve(key, scope, from);
    }

    /** Removes one value (the next less specific level, or the default, applies again). */
    @Transactional
    public void remove(String key, UUID valueId) {
        definition(key);
        if (jdbc.sql("delete from core.setting_value where tenant_id = :t and id = :id and setting_key = :k")
                .param("t", tenant()).param("id", valueId).param("k", key).update() == 0) {
            throw ApiException.notFound("Setting value", valueId);
        }
    }

    // ------------------------------------------------------------------------------------ validation

    /** Checks and normalises a value against the definition's type (BR-CORE-084 "typed values, validation"). */
    static Object validate(Definition d, Object value) {
        if (value == null) {
            throw ApiException.unprocessable("invalid-setting-value", d.key() + " needs a value");
        }
        String type = d.valueType();
        boolean ok = switch (type) {
            case "BOOL" -> value instanceof Boolean;
            case "INT" -> value instanceof Number n && new BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0;
            case "DECIMAL" -> value instanceof Number;
            case "TEXT" -> value instanceof String;
            case "ENUM" -> value instanceof String s && d.allowedValues().contains(s);
            case "DATE" -> value instanceof String s && isDate(s);
            default -> true;
        };
        if (!ok) {
            throw ApiException.unprocessable("invalid-setting-value", d.key() + " expects " + type
                    + ("ENUM".equals(type) ? " " + d.allowedValues() : "") + ", got: " + value);
        }
        return value;
    }

    private static boolean isDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private Scope checkScope(UUID tenant, String level, UUID legalEntityId, UUID locationId) {
        switch (level) {
            case "TENANT" -> {
                return Scope.tenant();
            }
            case "LEGAL_ENTITY" -> {
                if (legalEntityId == null || !jdbc.sql("select exists (select 1 from core.legal_entity where tenant_id = :t and id = :id)")
                        .param("t", tenant).param("id", legalEntityId).query(Boolean.class).single()) {
                    throw ApiException.unprocessable("unknown-legal-entity", "A valid legal entity is required");
                }
                return new Scope(legalEntityId, null);
            }
            default -> {
                UUID le = locationId == null ? null : jdbc.sql(
                                "select legal_entity_id from core.location where tenant_id = :t and id = :id")
                        .param("t", tenant).param("id", locationId).query(UUID.class).optional().orElse(null);
                if (le == null) {
                    throw ApiException.unprocessable("unknown-location", "A valid location is required");
                }
                return new Scope(le, locationId);
            }
        }
    }

    /** A location implies its legal entity. */
    private Scope normalise(Scope scope) {
        Scope s = scope == null ? Scope.tenant() : scope;
        if (s.locationId() != null && s.legalEntityId() == null) {
            UUID le = jdbc.sql("select legal_entity_id from core.location where tenant_id = :t and id = :id")
                    .param("t", tenant()).param("id", s.locationId()).query(UUID.class).optional().orElse(null);
            return new Scope(le, s.locationId());
        }
        return s;
    }

    // ------------------------------------------------------------------------------------ helpers

    private SettingView view(Definition d) {
        List<ValueView> values = jdbc.sql("""
                        select id, scope_level, legal_entity_id, location_id, value::text, valid_from, valid_to, version
                          from core.setting_value where tenant_id = :t and setting_key = :k
                         order by case scope_level when 'TENANT' then 0 when 'LEGAL_ENTITY' then 1 else 2 end, valid_from""")
                .param("t", tenant()).param("k", d.key())
                .query((rs, n) -> new ValueView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        d.sensitive() ? "***" : json.value(rs.getString(5)), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class), rs.getInt(8)))
                .list();
        Object tenantValue = resolve(d.key(), Scope.tenant(), clock.today()).value();
        return new SettingView(d.key(), d.ownerModule(), d.valueType(), d.allowedValues(), d.defaultValue(),
                d.allowedLevels(), d.effectiveDated(), d.sensitive(), d.descriptionVi(), d.descriptionEn(),
                d.sensitive() ? "***" : tenantValue, values);
    }

    private Definition definition(String key) {
        return definitions(key).stream().findFirst().orElseThrow(() -> ApiException.unprocessable("unknown-setting",
                "Setting " + key + " is not registered"));
    }

    private List<Definition> definitions() {
        return definitions(null);
    }

    private List<Definition> definitions(String key) {
        return jdbc.sql("""
                        select setting_key, owner_module, value_type, json_schema::text, default_value::text,
                               allowed_levels, is_effective_dated, is_sensitive, description_vi, description_en
                          from core.setting_definition
                         where is_active and (cast(:k as text) is null or setting_key = :k) order by setting_key""")
                .param("k", key)
                .query((rs, n) -> {
                    Map<String, Object> schema = json.map(rs.getString(4));
                    List<String> allowed = schema.get("enum") instanceof List<?> l
                            ? l.stream().map(String::valueOf).toList() : List.of();
                    return new Definition(rs.getString(1), rs.getString(2), rs.getString(3), allowed,
                            json.value(rs.getString(5)), List.of((String[]) rs.getArray(6).getArray()),
                            rs.getBoolean(7), rs.getBoolean(8), rs.getString(9), rs.getString(10));
                })
                .list();
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
