package vn.yourhrm.core.internal.application;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.shared.common.tenancy.PlatformContext;

/**
 * Registers the settings of all modules into the GLOBAL table {@code core.setting_definition} (US-CORE-085), like the
 * permission and process-type catalogues: the runtime role has SELECT only, so the upsert is tried in {@code auto}
 * mode and otherwise the release-job SQL is logged.
 */
@Component
public class SettingRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SettingRegistrar.class);
    private static final Set<String> MODULES = Set.of("saas", "core", "hr", "att", "pay", "rec");
    static final Set<String> TYPES = Set.of("BOOL", "INT", "DECIMAL", "TEXT", "ENUM", "DATE", "JSON");
    static final Set<String> LEVELS = Set.of("TENANT", "LEGAL_ENTITY", "LOCATION");

    private final List<SettingCatalogue> catalogues;
    private final JdbcClient jdbc;
    private final CoreJson json;
    private final TransactionTemplate tx;
    private final String mode;

    public SettingRegistrar(List<SettingCatalogue> catalogues, JdbcClient jdbc, CoreJson json,
            PlatformTransactionManager transactionManager,
            @Value("${yhrm.permissions.registration:auto}") String mode) {
        this.catalogues = catalogues;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.mode = mode;
    }

    /** Every setting declared in code, validated. */
    public List<SettingDefinition> declared() {
        Map<String, SettingDefinition> byKey = new LinkedHashMap<>();
        for (SettingCatalogue c : catalogues) {
            if (!MODULES.contains(c.module())) {
                throw new IllegalStateException("Unknown owner module " + c.module() + " in " + c.getClass());
            }
            for (SettingDefinition d : c.settings()) {
                if (!d.key().matches("^[a-z]+\\.[a-z0-9_.]+$") || !d.module().equals(c.module())) {
                    throw new IllegalStateException("Setting key " + d.key() + " must look like " + c.module() + ".name");
                }
                if (!TYPES.contains(d.valueType()) || !LEVELS.containsAll(d.allowedLevels())) {
                    throw new IllegalStateException("Setting " + d.key() + ": unknown type or level");
                }
                if ("ENUM".equals(d.valueType()) && !d.allowedValues().contains(String.valueOf(d.defaultValue()))) {
                    throw new IllegalStateException("Setting " + d.key() + ": the default must be an allowed value");
                }
                if (byKey.put(d.key(), d) != null) {
                    throw new IllegalStateException("Duplicate setting " + d.key());
                }
            }
        }
        return List.copyOf(byKey.values());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() throws Exception {
        if ("off".equalsIgnoreCase(mode)) {
            return;
        }
        List<SettingDefinition> all = declared();
        List<String> pending = PlatformContext.call("SETTING_REGISTRATION", () -> tx.execute(s ->
                all.stream().filter(d -> !upToDate(d)).map(SettingDefinition::key).toList()));
        if (pending.isEmpty()) {
            return;
        }
        if ("auto".equalsIgnoreCase(mode)) {
            try {
                PlatformContext.call("SETTING_REGISTRATION", () -> tx.execute(s -> {
                    all.forEach(d -> jdbc.sql(UPSERT).params(params(d)).update());
                    return null;
                }));
                log.info("Settings registered: {}", pending);
                return;
            } catch (RuntimeException ex) {
                log.debug("Upsert of core.setting_definition refused", ex);
            }
        }
        log.warn("{} setting definition(s) are missing or outdated in core.setting_definition: {}; the release job "
                + "(yhrm_platform) must register them", pending.size(), pending);
    }

    private boolean upToDate(SettingDefinition d) {
        return jdbc.sql("""
                        select exists (select 1 from core.setting_definition
                                        where setting_key = :k and owner_module = :m and value_type = :t
                                          and json_schema is not distinct from cast(:schema as jsonb)
                                          and default_value = cast(:def as jsonb) and allowed_levels = cast(:levels as text[])
                                          and is_effective_dated = :dated and is_sensitive = :sensitive
                                          and description_vi = :vi and description_en = :en and is_active)""")
                .params(params(d)).query(Boolean.class).single();
    }

    /** Parameters of {@link #UPSERT} for {@code d}. */
    public Map<String, Object> params(SettingDefinition d) {
        Map<String, Object> p = new HashMap<>();
        p.put("k", d.key());
        p.put("m", d.module());
        p.put("t", d.valueType());
        p.put("schema", d.allowedValues().isEmpty() ? null : json.write(Map.of("enum", d.allowedValues())));
        p.put("def", json.write(d.defaultValue()));
        p.put("levels", new TreeSet<>(d.allowedLevels()).toArray(String[]::new));
        p.put("dated", d.effectiveDated());
        p.put("sensitive", d.sensitive());
        p.put("vi", d.descriptionVi());
        p.put("en", d.descriptionEn());
        return p;
    }

    /** The release job's upsert of one definition (named parameters of {@link #params}). */
    public static final String UPSERT = """
            insert into core.setting_definition (setting_key, owner_module, value_type, json_schema, default_value,
                   allowed_levels, is_effective_dated, is_sensitive, description_vi, description_en)
            values (:k, :m, :t, cast(:schema as jsonb), cast(:def as jsonb), cast(:levels as text[]), :dated, :sensitive,
                    :vi, :en)
            on conflict (setting_key) do update
               set owner_module = excluded.owner_module, value_type = excluded.value_type,
                   json_schema = excluded.json_schema, default_value = excluded.default_value,
                   allowed_levels = excluded.allowed_levels, is_effective_dated = excluded.is_effective_dated,
                   is_sensitive = excluded.is_sensitive, description_vi = excluded.description_vi,
                   description_en = excluded.description_en, is_active = true""";
}
