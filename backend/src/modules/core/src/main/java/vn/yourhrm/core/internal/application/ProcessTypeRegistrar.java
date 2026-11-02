package vn.yourhrm.core.internal.application;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.shared.common.tenancy.PlatformContext;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * Registers the process types of all modules into the GLOBAL table {@code core.process_type} (BR-CORE-050,
 * US-CORE-050), the same way as the permission catalogue: the runtime role has SELECT only, so in {@code auto}
 * mode the upsert is tried and, when refused, the SQL for the release job is logged. The vendor-seeded default
 * definitions stay in code ({@link #declared(String)}) and are materialised per tenant on first use.
 */
@Component
public class ProcessTypeRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ProcessTypeRegistrar.class);
    private static final Set<String> MODULES = Set.of("saas", "core", "hr", "att", "pay", "rec");

    /** Always allowed, whatever the process type lists (BR-CORE-054). */
    static final Set<WorkflowAction> BASE_ACTIONS = Set.of(WorkflowAction.APPROVE, WorkflowAction.REJECT,
            WorkflowAction.WITHDRAW, WorkflowAction.COMMENT);

    public enum Outcome { UP_TO_DATE, REGISTERED, PENDING_RELEASE_JOB, DISABLED }

    private final List<ProcessTypeCatalogue> catalogues;
    private final JdbcClient jdbc;
    private final CoreJson json;
    private final TransactionTemplate tx;
    private final String mode;

    public ProcessTypeRegistrar(List<ProcessTypeCatalogue> catalogues, JdbcClient jdbc, CoreJson json,
            PlatformTransactionManager transactionManager,
            @Value("${yhrm.permissions.registration:auto}") String mode) {
        this.catalogues = catalogues;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.mode = mode;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        register();
    }

    /** Every process type declared in code, validated (known module, code prefix = module, unique). */
    public Map<String, Declared> declared() {
        Map<String, Declared> byCode = new HashMap<>();
        for (ProcessTypeCatalogue c : catalogues) {
            if (!MODULES.contains(c.module())) {
                throw new IllegalStateException("Unknown owner module " + c.module() + " in " + c.getClass());
            }
            for (ProcessTypeDefinition d : c.processTypes()) {
                if (!d.module().equals(c.module())) {
                    throw new IllegalStateException("Process type " + d.code() + " must start with " + c.module() + ".");
                }
                if (byCode.put(d.code(), new Declared(c.module(), d)) != null) {
                    throw new IllegalStateException("Duplicate process type " + d.code());
                }
            }
        }
        return byCode;
    }

    public Optional<Declared> declared(String code) {
        return Optional.ofNullable(declared().get(code));
    }

    public Outcome register() {
        if ("off".equalsIgnoreCase(mode)) {
            return Outcome.DISABLED;
        }
        List<Declared> all = List.copyOf(declared().values());
        if (all.isEmpty()) {
            return Outcome.UP_TO_DATE;
        }
        try {
            List<String> pending = PlatformContext.call("PROCESS_TYPE_REGISTRATION",
                    () -> tx.execute(s -> all.stream().filter(d -> !upToDate(d)).map(d -> d.def().code()).toList()));
            if (pending.isEmpty()) {
                return Outcome.UP_TO_DATE;
            }
            if ("auto".equalsIgnoreCase(mode)) {
                try {
                    PlatformContext.call("PROCESS_TYPE_REGISTRATION", () -> tx.execute(s -> {
                        all.forEach(this::upsert);
                        return null;
                    }));
                    log.info("Process types registered: {}", pending);
                    return Outcome.REGISTERED;
                } catch (RuntimeException ex) {
                    log.debug("Upsert of core.process_type refused", ex);
                }
            }
            log.warn("""
                    {} process type(s) are missing or outdated in core.process_type: {}.
                    The runtime role cannot write this global table; run as yhrm_platform (release job):
                    {}""", pending.size(), pending, all.stream().map(this::upsertSqlLiteral).toList());
            return Outcome.PENDING_RELEASE_JOB;
        } catch (Exception ex) {
            throw new IllegalStateException("Process type registration failed", ex);
        }
    }

    private boolean upToDate(Declared d) {
        ProcessTypeDefinition p = d.def();
        return jdbc.sql("""
                        select exists (select 1 from core.process_type
                                        where process_type_code = :c and owner_module = :m and name_vi = :vi
                                          and name_en = :en and summary_schema = cast(:schema as jsonb)
                                          and condition_fields = cast(:fields as jsonb)
                                          and allowed_actions = cast(:actions as text[])
                                          and allows_auto_approve = :auto and requires_step_up = :stepup
                                          and is_active)""")
                .params(params(d)).query(Boolean.class).single();
    }

    private void upsert(Declared d) {
        jdbc.sql(UPSERT).params(params(d)).update();
    }

    private Map<String, Object> params(Declared d) {
        ProcessTypeDefinition p = d.def();
        Map<String, Object> m = new HashMap<>();
        m.put("c", p.code());
        m.put("m", d.module());
        m.put("vi", p.nameVi());
        m.put("en", p.nameEn());
        m.put("schema", json.write(p.summarySchema()));
        m.put("fields", json.write(p.conditionFields()));
        m.put("actions", actions(p));
        m.put("auto", p.allowsAutoApprove());
        m.put("stepup", p.requiresStepUp());
        return m;
    }

    /** Allowed actions as stored: the base set plus the declared ones, sorted. */
    static String[] actions(ProcessTypeDefinition p) {
        TreeSet<String> all = new TreeSet<>();
        BASE_ACTIONS.forEach(a -> all.add(a.name()));
        p.allowedActions().forEach(a -> all.add(a.name()));
        return all.toArray(String[]::new);
    }

    /** The release job's upsert of one declared process type, as an SQL literal (yhrm_platform / migrator). */
    public String upsertSqlLiteral(Declared d) {
        ProcessTypeDefinition p = d.def();
        return "insert into core.process_type (process_type_code, owner_module, name_vi, name_en, summary_schema, "
                + "condition_fields, allowed_actions, allows_auto_approve, requires_step_up) values ("
                + lit(p.code()) + ", " + lit(d.module()) + ", " + lit(p.nameVi()) + ", " + lit(p.nameEn()) + ", "
                + lit(json.write(p.summarySchema())) + "::jsonb, " + lit(json.write(p.conditionFields())) + "::jsonb, "
                + "ARRAY[" + String.join(", ", Arrays.stream(actions(p)).map(ProcessTypeRegistrar::lit).toList())
                + "], " + p.allowsAutoApprove() + ", " + p.requiresStepUp() + ") on conflict (process_type_code) do update "
                + "set owner_module = excluded.owner_module, name_vi = excluded.name_vi, name_en = excluded.name_en, "
                + "summary_schema = excluded.summary_schema, condition_fields = excluded.condition_fields, "
                + "allowed_actions = excluded.allowed_actions, allows_auto_approve = excluded.allows_auto_approve, "
                + "requires_step_up = excluded.requires_step_up, is_active = true;";
    }

    private static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static final String UPSERT = """
            insert into core.process_type (process_type_code, owner_module, name_vi, name_en, summary_schema,
                                           condition_fields, allowed_actions, allows_auto_approve, requires_step_up)
            values (:c, :m, :vi, :en, cast(:schema as jsonb), cast(:fields as jsonb), cast(:actions as text[]),
                    :auto, :stepup)
            on conflict (process_type_code) do update
               set owner_module = excluded.owner_module, name_vi = excluded.name_vi, name_en = excluded.name_en,
                   summary_schema = excluded.summary_schema, condition_fields = excluded.condition_fields,
                   allowed_actions = excluded.allowed_actions, allows_auto_approve = excluded.allows_auto_approve,
                   requires_step_up = excluded.requires_step_up, is_active = true""";

    public record Declared(String module, ProcessTypeDefinition def) {
    }
}
