package vn.yourhrm.core.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;

/**
 * Workflow definitions (US-CORE-051; the no-code designer of BR-CORE-051 is P2). Two sources:
 * <ul>
 *   <li>the vendor default of each process type, materialised for a tenant on its first submit;</li>
 *   <li>tenant flows loaded by support through the API (no code change).</li>
 * </ul>
 * Publishing creates a new immutable version; the previous published version ends where the new one starts, and
 * running instances keep the version they started with. At submit the most specific matching definition wins:
 * most condition dimensions first, then the lowest {@code match_priority}, then the code.
 */
@Service
public class WorkflowDefinitionService {

    /** Step resolvers that need data Phase 1 does not have; they resolve to nothing (fallback applies). */
    static final Set<ApproverResolver.Type> UNSUPPORTED = Set.of(ApproverResolver.Type.POSITION_HOLDER,
            ApproverResolver.Type.FUNCTIONAL_MANAGER);

    private final JdbcClient jdbc;
    private final CoreJson json;
    private final ProcessTypeRegistrar processTypes;

    public WorkflowDefinitionService(JdbcClient jdbc, CoreJson json, ProcessTypeRegistrar processTypes) {
        this.jdbc = jdbc;
        this.json = json;
        this.processTypes = processTypes;
    }

    /**
     * Applicability (DDL {@code workflow_definition_version.conditions}); empty sets match anything. Org units match
     * with their subtree; {@code fields} keys must be condition fields of the process type.
     */
    public record Conditions(Set<UUID> legalEntityIds, Set<UUID> orgUnitIds, Set<UUID> locationIds,
            Set<UUID> employeeCategoryIds, Map<String, List<String>> fields) {

        public Conditions {
            legalEntityIds = legalEntityIds == null ? Set.of() : Set.copyOf(legalEntityIds);
            orgUnitIds = orgUnitIds == null ? Set.of() : Set.copyOf(orgUnitIds);
            locationIds = locationIds == null ? Set.of() : Set.copyOf(locationIds);
            employeeCategoryIds = employeeCategoryIds == null ? Set.of() : Set.copyOf(employeeCategoryIds);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }

        public static Conditions none() {
            return new Conditions(null, null, null, null, null);
        }

        int specificity() {
            return (legalEntityIds.isEmpty() ? 0 : 1) + (orgUnitIds.isEmpty() ? 0 : 1) + (locationIds.isEmpty() ? 0 : 1)
                    + (employeeCategoryIds.isEmpty() ? 0 : 1) + fields.size();
        }
    }

    public record DefinitionView(UUID id, String processTypeCode, String code, String nameVi, String nameEn,
            int matchPriority, String status, List<VersionView> versions) {
    }

    public record VersionView(UUID id, int versionNo, String status, LocalDate validFrom, LocalDate validTo,
            Conditions conditions, List<StepTemplate> steps, String onResolverFailure) {
    }

    /** The definition version chosen for a request. */
    public record Selected(UUID versionId, String code, List<StepTemplate> steps, String onResolverFailure) {
    }

    // ------------------------------------------------------------------------------------ publishing

    /** Creates the definition if needed and publishes a new version of it from {@code validFrom}. */
    @Transactional
    public DefinitionView publish(String processTypeCode, String code, String nameVi, String nameEn,
            Integer matchPriority, Conditions conditions, List<StepTemplate> steps, String onResolverFailure,
            LocalDate validFrom) {
        UUID tenant = TenantContext.current().tenantId();
        List<String> conditionFields = processTypeFields(processTypeCode);
        validate(conditions, steps, onResolverFailure, conditionFields);
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('core.workflow_definition:' || cast(:t as text), 0))")
                .param("t", tenant).query((rs, n) -> 1).single();

        record Def(UUID id, String processType) {
        }
        Optional<Def> existing = jdbc.sql("""
                        select id, process_type_code from core.workflow_definition where tenant_id = :t and code = :c""")
                .param("t", tenant).param("c", code)
                .query((rs, n) -> new Def(rs.getObject(1, UUID.class), rs.getString(2))).optional();
        UUID defId;
        if (existing.isPresent()) {
            if (!existing.get().processType().equals(processTypeCode)) {
                throw ApiException.conflict("workflow-definition-code-taken",
                        "Definition " + code + " belongs to process type " + existing.get().processType());
            }
            defId = existing.get().id();
            jdbc.sql("""
                            update core.workflow_definition set name_vi = :vi, name_en = :en, match_priority = :p,
                                   status = 'ACTIVE', updated_by = :actor, version = version + 1
                             where tenant_id = :t and id = :id""")
                    .param("vi", nameVi).param("en", nameEn).param("p", matchPriority == null ? 100 : matchPriority)
                    .param("actor", TenantContext.current().userAccountId()).param("t", tenant).param("id", defId)
                    .update();
        } else {
            defId = Ids.newId();
            jdbc.sql("""
                            insert into core.workflow_definition (tenant_id, id, process_type_code, code, name_vi, name_en,
                                   match_priority, created_by, updated_by)
                            values (:t, :id, :pt, :c, :vi, :en, :p, :actor, :actor)""")
                    .param("t", tenant).param("id", defId).param("pt", processTypeCode).param("c", code)
                    .param("vi", nameVi).param("en", nameEn).param("p", matchPriority == null ? 100 : matchPriority)
                    .param("actor", TenantContext.current().userAccountId())
                    .update();
        }
        if (jdbc.sql("""
                        select exists (select 1 from core.workflow_definition_version
                                        where tenant_id = :t and workflow_definition_id = :d and status = 'PUBLISHED'
                                          and valid_from >= :from)""")
                .param("t", tenant).param("d", defId).param("from", validFrom).query(Boolean.class).single()) {
            throw ApiException.conflict("future-version-exists",
                    "A version of " + code + " already starts on or after " + validFrom);
        }
        // the running version ends where the new one starts (published versions only change valid_to / status)
        jdbc.sql("""
                        update core.workflow_definition_version set valid_to = :from, updated_by = :actor
                         where tenant_id = :t and workflow_definition_id = :d and status = 'PUBLISHED'
                           and (valid_to is null or valid_to > :from)""")
                .param("from", validFrom).param("actor", TenantContext.current().userAccountId())
                .param("t", tenant).param("d", defId).update();
        int versionNo = jdbc.sql("""
                        select coalesce(max(version_no), 0) + 1 from core.workflow_definition_version
                         where tenant_id = :t and workflow_definition_id = :d""")
                .param("t", tenant).param("d", defId).query(Integer.class).single();
        String stepsJson = json.write(steps);
        String conditionsJson = json.write(conditions);
        jdbc.sql("""
                        insert into core.workflow_definition_version (tenant_id, id, workflow_definition_id, version_no,
                               status, conditions, steps, on_resolver_failure, valid_from, published_at, published_by,
                               content_sha256, created_by, updated_by)
                        values (:t, :id, :d, :n, 'PUBLISHED', cast(:cond as jsonb), cast(:steps as jsonb), :fallback,
                                :from, now(), :actor, :sha, :actor, :actor)""")
                .param("t", tenant).param("id", Ids.newId()).param("d", defId).param("n", versionNo)
                .param("cond", conditionsJson).param("steps", stepsJson).param("fallback", onResolverFailure)
                .param("from", validFrom).param("actor", TenantContext.current().userAccountId())
                .param("sha", sha256(conditionsJson + "\n" + stepsJson + "\n" + onResolverFailure))
                .update();
        return get(defId);
    }

    /** Materialises the process type's vendor default for this tenant if it has no definition (US-CORE-051). */
    @Transactional
    public void ensureVendorDefinition(String processTypeCode) {
        UUID tenant = TenantContext.current().tenantId();
        boolean hasOwn = jdbc.sql("""
                        select exists (select 1 from core.workflow_definition where tenant_id = :t and process_type_code = :p)""")
                .param("t", tenant).param("p", processTypeCode).query(Boolean.class).single();
        if (hasOwn) {
            return;
        }
        processTypes.declared(processTypeCode).filter(d -> !d.def().defaultSteps().isEmpty()).ifPresent(d ->
                publish(processTypeCode, "VENDOR." + processTypeCode, d.def().nameVi(), d.def().nameEn(), 1000,
                        Conditions.none(), d.def().defaultSteps(), "ESCALATE_UP", LocalDate.of(1900, 1, 1)));
    }

    // ------------------------------------------------------------------------------------ reads

    public record ProcessTypeView(String code, String ownerModule, String nameVi, String nameEn,
            List<String> conditionFields, List<String> allowedActions, boolean allowsAutoApprove,
            boolean requiresStepUp, boolean active, boolean hasVendorDefault) {
    }

    /** The registry of process types (US-CORE-050 AC: visible to support). */
    @Transactional(readOnly = true)
    public List<ProcessTypeView> processTypes() {
        return jdbc.sql("""
                        select process_type_code, owner_module, name_vi, name_en, condition_fields::text, allowed_actions,
                               allows_auto_approve, requires_step_up, is_active
                          from core.process_type order by process_type_code""")
                .query((rs, n) -> new ProcessTypeView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        json.listOfStrings(rs.getString(5)), List.of((String[]) rs.getArray(6).getArray()),
                        rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9),
                        processTypes.declared(rs.getString(1)).map(d -> !d.def().defaultSteps().isEmpty())
                                .orElse(false)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<DefinitionView> list(String processTypeCode) {
        return jdbc.sql("""
                        select id from core.workflow_definition where tenant_id = :t
                           and (cast(:p as text) is null or process_type_code = :p) order by process_type_code, code""")
                .param("t", TenantContext.current().tenantId()).param("p", processTypeCode)
                .query(UUID.class).list().stream().map(this::get).toList();
    }

    @Transactional(readOnly = true)
    public DefinitionView get(UUID id) {
        UUID tenant = TenantContext.current().tenantId();
        List<VersionView> versions = jdbc.sql("""
                        select id, version_no, status, valid_from, valid_to, conditions::text, steps::text,
                               on_resolver_failure
                          from core.workflow_definition_version where tenant_id = :t and workflow_definition_id = :d
                         order by version_no""")
                .param("t", tenant).param("d", id)
                .query((rs, n) -> new VersionView(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), conditions(rs.getString(6)),
                        json.steps(rs.getString(7)), rs.getString(8)))
                .list();
        return jdbc.sql("""
                        select id, process_type_code, code, name_vi, name_en, match_priority, status
                          from core.workflow_definition where tenant_id = :t and id = :id""")
                .param("t", tenant).param("id", id)
                .query((rs, n) -> new DefinitionView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7), versions))
                .optional().orElseThrow(() -> ApiException.notFound("Workflow definition", id));
    }

    // ------------------------------------------------------------------------------------ matching

    /** The most specific published version matching {@code r} on {@code date}. */
    @Transactional(readOnly = true)
    public Optional<Selected> match(StartRequest r, LocalDate date) {
        UUID tenant = TenantContext.current().tenantId();
        record Candidate(UUID versionId, String code, int priority, Conditions conditions, String steps,
                String fallback) {
        }
        List<Candidate> candidates = jdbc.sql("""
                        select v.id, d.code, d.match_priority, v.conditions::text, v.steps::text, v.on_resolver_failure
                          from core.workflow_definition d
                          join core.workflow_definition_version v
                            on v.tenant_id = d.tenant_id and v.workflow_definition_id = d.id
                         where d.tenant_id = :t and d.process_type_code = :p and d.status = 'ACTIVE'
                           and v.status = 'PUBLISHED' and v.valid_during @> cast(:d as date)""")
                .param("t", tenant).param("p", r.processTypeCode()).param("d", date)
                .query((rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        conditions(rs.getString(4)), rs.getString(5), rs.getString(6)))
                .list();
        return candidates.stream()
                .filter(c -> matches(tenant, c.conditions(), r, date))
                .min(Comparator.comparingInt((Candidate c) -> -c.conditions().specificity())
                        .thenComparingInt(Candidate::priority).thenComparing(Candidate::code))
                .map(c -> new Selected(c.versionId(), c.code(), json.steps(c.steps()), c.fallback()));
    }

    private boolean matches(UUID tenant, Conditions c, StartRequest r, LocalDate date) {
        if (!c.legalEntityIds().isEmpty() && !c.legalEntityIds().contains(r.legalEntityId())) {
            return false;
        }
        if (!c.locationIds().isEmpty() && !c.locationIds().contains(r.locationId())) {
            return false;
        }
        if (!c.employeeCategoryIds().isEmpty() && !c.employeeCategoryIds().contains(r.employeeCategoryId())) {
            return false;
        }
        for (Map.Entry<String, List<String>> f : c.fields().entrySet()) {
            Object value = r.conditionValues().get(f.getKey());
            if (value == null || !f.getValue().contains(String.valueOf(value))) {
                return false;
            }
        }
        if (!c.orgUnitIds().isEmpty()) {
            // the request's org unit lies in the subtree of one of the listed units on that date
            return r.orgUnitId() != null && jdbc.sql("""
                            select exists (select 1 from core.org_unit_version u
                                             join core.org_unit_version a
                                               on a.tenant_id = u.tenant_id and u.path <@ a.path
                                              and a.valid_during @> cast(:d as date)
                                            where u.tenant_id = :t and u.org_unit_id = :unit
                                              and u.valid_during @> cast(:d as date) and a.org_unit_id in (:roots))""")
                    .param("t", tenant).param("unit", r.orgUnitId()).param("d", date)
                    .param("roots", List.copyOf(c.orgUnitIds())).query(Boolean.class).single();
        }
        return true;
    }

    // ------------------------------------------------------------------------------------ validation

    private List<String> processTypeFields(String processTypeCode) {
        return jdbc.sql("select condition_fields::text from core.process_type where process_type_code = :p and is_active")
                .param("p", processTypeCode).query(String.class).optional()
                .map(json::listOfStrings)
                .orElseThrow(() -> ApiException.unprocessable("unknown-process-type",
                        "Process type " + processTypeCode + " is not registered or inactive"));
    }

    private static void validate(Conditions c, List<StepTemplate> steps, String fallback, List<String> fields) {
        if (steps == null || steps.isEmpty()) {
            throw ApiException.unprocessable("invalid-steps", "A definition needs at least one step");
        }
        Set<String> keys = new HashSet<>();
        for (StepTemplate s : steps) {
            if (!keys.add(s.stepKey())) {
                throw ApiException.unprocessable("invalid-steps", "Duplicate step key " + s.stepKey());
            }
            ApproverResolver res = s.resolver();
            String missing = switch (res.type()) {
                case MANAGER_N_UP -> res.params().containsKey("levels") ? null : "levels";
                case ORG_UNIT_MANAGER -> res.params().containsKey("orgUnitId") ? null : "orgUnitId";
                case ROLE_IN_SCOPE -> res.params().containsKey("roleCode") ? null : "roleCode";
                case USER -> res.params().containsKey("userId") ? null : "userId";
                case REQUEST_FIELD -> res.params().containsKey("field") ? null : "field";
                default -> null;
            };
            if (missing != null) {
                throw ApiException.unprocessable("invalid-steps",
                        "Step " + s.stepKey() + ": resolver " + res.type() + " needs parameter " + missing);
            }
        }
        if (!List.of("SKIP", "ESCALATE_UP", "LE_HR_ADMIN", "FAIL").contains(fallback)) {
            throw ApiException.unprocessable("invalid-fallback", "onResolverFailure must be SKIP, ESCALATE_UP, "
                    + "LE_HR_ADMIN or FAIL");
        }
        List<String> unknown = new ArrayList<>(c.fields().keySet());
        unknown.removeAll(fields);
        if (!unknown.isEmpty()) {
            throw ApiException.unprocessable("unknown-condition-field",
                    "Not condition fields of the process type: " + unknown, Map.of("fields", unknown));
        }
    }

    private Conditions conditions(String value) {
        Map<String, Object> m = json.map(value);
        return new Conditions(uuids(m.get("legalEntityIds")), uuids(m.get("orgUnitIds")), uuids(m.get("locationIds")),
                uuids(m.get("employeeCategoryIds")), fieldMap(m.get("fields")));
    }

    private static Set<UUID> uuids(Object o) {
        Set<UUID> out = new HashSet<>();
        if (o instanceof List<?> list) {
            list.forEach(x -> out.add(UUID.fromString(String.valueOf(x))));
        }
        return out;
    }

    private static Map<String, List<String>> fieldMap(Object o) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k),
                    v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(String.valueOf(v))));
        }
        return out;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
