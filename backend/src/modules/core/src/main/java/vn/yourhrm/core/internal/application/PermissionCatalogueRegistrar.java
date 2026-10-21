package vn.yourhrm.core.internal.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.shared.auth.api.PermissionCatalogue;
import vn.yourhrm.shared.auth.api.PermissionDefinition;
import vn.yourhrm.shared.common.tenancy.PlatformContext;

/**
 * Registers the permission catalogues of all modules into the GLOBAL table {@code core.permission}
 * at start-up (US-CORE-033): idempotent upsert; codes that disappeared from a module's catalogue are
 * deactivated ({@code is_active = false}), never deleted.
 *
 * <p>Grants: per 002_core.sql the runtime role {@code yhrm_app_core} has only SELECT on core.permission
 * (written by the release job / platform tooling, role {@code yhrm_platform} or the migrator). Therefore:
 * <ul>
 *   <li>{@code yhrm.permissions.registration=auto} (default): try the upsert; on "permission denied" fall
 *       back to verification;</li>
 *   <li>verification compares code and database and logs the exact SQL the release job must run
 *       (the documented seed path; {@link #renderUpsertSql}).</li>
 * </ul>
 */
@Component
public class PermissionCatalogueRegistrar {

    private static final Logger log = LoggerFactory.getLogger(PermissionCatalogueRegistrar.class);
    private static final Set<String> MODULES = Set.of("saas", "core", "hr", "att", "pay", "rec");

    public enum Outcome { UP_TO_DATE, REGISTERED, PENDING_RELEASE_JOB, DISABLED }

    private final List<PermissionCatalogue> catalogues;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String mode;
    private volatile Outcome lastOutcome;
    private volatile List<String> lastPendingCodes = List.of();

    public PermissionCatalogueRegistrar(List<PermissionCatalogue> catalogues, JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${yhrm.permissions.registration:auto}") String mode) {
        this.catalogues = catalogues;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.mode = mode;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        register();
    }

    /** All permissions declared in code, validated (unique codes, known owner modules). */
    public List<Declared> declared() {
        Map<String, Declared> byCode = new HashMap<>();
        for (PermissionCatalogue c : catalogues) {
            if (!MODULES.contains(c.module())) {
                throw new IllegalStateException("Unknown owner module " + c.module() + " in " + c.getClass());
            }
            for (PermissionDefinition d : c.permissions()) {
                if (!d.code().startsWith(c.module() + ".")) {
                    throw new IllegalStateException("Permission " + d.code() + " must start with " + c.module() + ".");
                }
                if (byCode.put(d.code(), new Declared(c.module(), d)) != null) {
                    throw new IllegalStateException("Duplicate permission code " + d.code());
                }
            }
        }
        return byCode.values().stream().sorted((a, b) -> a.def().code().compareTo(b.def().code())).toList();
    }

    public Outcome register() {
        if ("off".equalsIgnoreCase(mode)) {
            lastOutcome = Outcome.DISABLED;
            return lastOutcome;
        }
        List<Declared> declared = declared();
        Set<String> owners = new HashSet<>();
        declared.forEach(d -> owners.add(d.module()));
        try {
            List<String> pending = PlatformContext.call("PERMISSION_REGISTRATION",
                    () -> tx.execute(s -> pendingChanges(declared, owners)));
            if (pending.isEmpty()) {
                lastPendingCodes = List.of();
                lastOutcome = Outcome.UP_TO_DATE;
                log.info("Permission catalogue up to date ({} permissions)", declared.size());
                return lastOutcome;
            }
            if ("auto".equalsIgnoreCase(mode)) {
                try {
                    PlatformContext.call("PERMISSION_REGISTRATION", () -> tx.execute(s -> {
                        upsert(declared, owners);
                        return null;
                    }));
                    lastPendingCodes = List.of();
                    lastOutcome = Outcome.REGISTERED;
                    log.info("Permission catalogue registered: {}", pending);
                    return lastOutcome;
                } catch (RuntimeException ex) {
                    log.debug("Upsert of core.permission refused", ex);
                }
            }
            lastPendingCodes = pending;
            lastOutcome = Outcome.PENDING_RELEASE_JOB;
            log.warn("""
                    {} permission(s) of the code catalogue are missing or outdated in core.permission: {}.
                    The runtime role cannot write this global table (002_core.sql grants SELECT only).
                    Run the following as yhrm_platform / migrator (release job), or load dev/seed-dev-tenant.sql:
                    {}""", pending.size(), pending, renderUpsertSql(declared));
            return lastOutcome;
        } catch (Exception ex) {
            throw new IllegalStateException("Permission catalogue registration failed", ex);
        }
    }

    public Outcome lastOutcome() {
        return lastOutcome;
    }

    public List<String> lastPendingCodes() {
        return lastPendingCodes;
    }

    private List<String> pendingChanges(List<Declared> declared, Set<String> owners) {
        Map<String, String> existing = new HashMap<>();
        jdbc.sql("""
                        select permission_code, owner_module, function_code, action, name_vi, name_en,
                               is_sensitive, is_privileged, requires_step_up, is_active
                          from core.permission""")
                .query(rs -> {
                    existing.put(rs.getString(1), String.join("|", rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), String.valueOf(rs.getBoolean(7)),
                            String.valueOf(rs.getBoolean(8)), String.valueOf(rs.getBoolean(9)),
                            String.valueOf(rs.getBoolean(10))));
                });
        List<String> pending = new ArrayList<>();
        Set<String> declaredCodes = new HashSet<>();
        for (Declared d : declared) {
            declaredCodes.add(d.def().code());
            if (!fingerprint(d).equals(existing.get(d.def().code()))) {
                pending.add(d.def().code());
            }
        }
        existing.forEach((code, fp) -> {
            String owner = fp.substring(0, fp.indexOf('|'));
            if (owners.contains(owner) && !declaredCodes.contains(code) && fp.endsWith("|true")) {
                pending.add(code + " (deactivate)");
            }
        });
        return pending;
    }

    private void upsert(List<Declared> declared, Set<String> owners) {
        for (Declared d : declared) {
            PermissionDefinition p = d.def();
            jdbc.sql(UPSERT_SQL)
                    .param("code", p.code()).param("module", d.module()).param("fn", p.functionCode())
                    .param("action", p.action().name()).param("vi", p.nameVi()).param("en", p.nameEn())
                    .param("sensitive", p.sensitive()).param("privileged", p.privileged())
                    .param("stepup", p.requiresStepUp())
                    .update();
        }
        List<String> codes = declared.stream().map(d -> d.def().code()).toList();
        jdbc.sql("""
                        update core.permission set is_active = false
                         where owner_module in (:owners) and permission_code not in (:codes) and is_active""")
                .param("owners", List.copyOf(owners)).param("codes", codes)
                .update();
    }

    private static String fingerprint(Declared d) {
        PermissionDefinition p = d.def();
        return String.join("|", d.module(), p.functionCode(), p.action().name(), p.nameVi(), p.nameEn(),
                String.valueOf(p.sensitive()), String.valueOf(p.privileged()), String.valueOf(p.requiresStepUp()),
                "true");
    }

    private static final String UPSERT_SQL = """
            insert into core.permission (permission_code, owner_module, function_code, action, name_vi, name_en,
                                         is_sensitive, is_privileged, requires_step_up, is_active)
            values (:code, :module, :fn, :action, :vi, :en, :sensitive, :privileged, :stepup, true)
            on conflict (permission_code) do update
               set owner_module = excluded.owner_module, function_code = excluded.function_code,
                   action = excluded.action, name_vi = excluded.name_vi, name_en = excluded.name_en,
                   is_sensitive = excluded.is_sensitive, is_privileged = excluded.is_privileged,
                   requires_step_up = excluded.requires_step_up, is_active = true""";

    /** Plain SQL (literals) for the release job / seed scripts: idempotent upsert of the declared catalogue. */
    public static String renderUpsertSql(List<Declared> declared) {
        StringBuilder sb = new StringBuilder();
        sb.append("insert into core.permission (permission_code, owner_module, function_code, action, name_vi, ")
                .append("name_en, is_sensitive, is_privileged, requires_step_up, is_active) values\n");
        for (int i = 0; i < declared.size(); i++) {
            PermissionDefinition p = declared.get(i).def();
            sb.append("  (").append(lit(p.code())).append(", ").append(lit(declared.get(i).module())).append(", ")
                    .append(lit(p.functionCode())).append(", ").append(lit(p.action().name())).append(", ")
                    .append(lit(p.nameVi())).append(", ").append(lit(p.nameEn())).append(", ")
                    .append(p.sensitive()).append(", ").append(p.privileged()).append(", ")
                    .append(p.requiresStepUp()).append(", true)")
                    .append(i + 1 < declared.size() ? ",\n" : "\n");
        }
        sb.append("on conflict (permission_code) do update set owner_module = excluded.owner_module, ")
                .append("function_code = excluded.function_code, action = excluded.action, ")
                .append("name_vi = excluded.name_vi, name_en = excluded.name_en, is_sensitive = excluded.is_sensitive, ")
                .append("is_privileged = excluded.is_privileged, requires_step_up = excluded.requires_step_up, ")
                .append("is_active = true;\n");
        return sb.toString();
    }

    private static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** A declared permission and its owner module. */
    public record Declared(String module, PermissionDefinition def) {
    }
}
