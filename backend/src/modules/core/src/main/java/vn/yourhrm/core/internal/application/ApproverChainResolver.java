package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.internal.domain.SystemRoleCodes;
import vn.yourhrm.core.internal.infra.AuthorisationQueries;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.workflow.api.ApproverResolver;
import vn.yourhrm.shared.workflow.api.StepTemplate;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;

/**
 * Resolves the whole approver chain at submit (BR-CORE-052, US-CORE-053; snapshot per BR-CORE-058), with the
 * organisation as of the submit date. Every step records how it was resolved and why ({@code reason}).
 *
 * <p>Fallback (BR-CORE-056, US-CORE-056): when a step yields nobody usable (no one found, inactive, or only the
 * requester / the subject), the definition's {@code on_resolver_failure} applies: SKIP the step, ESCALATE_UP to the
 * next manager above the step's level, LE_HR_ADMIN (holders of the HR Admin role covering the request), or FAIL the
 * submit. A step whose approvers equal the previous step's is skipped (duplicate approver).
 */
@Component
public class ApproverChainResolver {

    private final JdbcClient jdbc;
    private final AuthorisationQueries queries;
    private final CoreAuthorizationService authorization;

    public ApproverChainResolver(JdbcClient jdbc, AuthorisationQueries queries,
            CoreAuthorizationService authorization) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.authorization = authorization;
    }

    /** One resolved step of the chain snapshot. {@code via}: RESOLVER, FALLBACK or SKIPPED. */
    public record ResolvedStep(int stepNo, String stepKey, String nameVi, String nameEn, List<UUID> approvers,
            String via, String reason, boolean allowReturn) {

        public boolean skipped() {
            return "SKIPPED".equals(via);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepNo", stepNo);
            m.put("stepKey", stepKey);
            m.put("nameVi", nameVi);
            m.put("nameEn", nameEn);
            m.put("approvers", approvers.stream().map(UUID::toString).toList());
            m.put("via", via);
            m.put("reason", reason);
            m.put("allowReturn", allowReturn);
            return m;
        }

        @SuppressWarnings("unchecked")
        static ResolvedStep fromMap(Map<String, Object> m) {
            return new ResolvedStep(((Number) m.get("stepNo")).intValue(), (String) m.get("stepKey"),
                    (String) m.get("nameVi"), (String) m.get("nameEn"),
                    ((List<Object>) m.get("approvers")).stream().map(o -> UUID.fromString(String.valueOf(o))).toList(),
                    (String) m.get("via"), (String) m.get("reason"), Boolean.TRUE.equals(m.get("allowReturn")));
        }
    }

    public List<ResolvedStep> resolve(List<StepTemplate> steps, String onFailure, StartRequest r, UUID requester,
            LocalDate date) {
        UUID tenant = TenantContext.current().tenantId();
        Set<UUID> excluded = new LinkedHashSet<>();
        excluded.add(requester);
        if (r.onBehalfOfUserId() != null) {
            excluded.add(r.onBehalfOfUserId());
        }
        List<UUID> managerChain = managerChain(tenant, r.orgUnitId(), excluded, date);
        List<ResolvedStep> out = new ArrayList<>();
        List<UUID> previous = List.of();
        int stepNo = 0;
        for (StepTemplate s : steps) {
            stepNo++;
            ApproverResolver res = s.resolver();
            Found found = find(tenant, res, s.stepKey(), r, managerChain, date);
            List<UUID> usable = found.users().stream().filter(u -> !excluded.contains(u))
                    .filter(u -> queries.mayAccess(tenant, u)).distinct().toList();
            String via = "RESOLVER";
            String reason = res.type() + ": " + found.reason();
            if (usable.isEmpty()) {
                String why = found.users().isEmpty() ? "nobody found"
                        : "only the requester / subject or inactive users " + found.users();
                switch (onFailure) {
                    case "SKIP" -> {
                        out.add(new ResolvedStep(stepNo, s.stepKey(), s.nameVi(), s.nameEn(), List.of(), "SKIPPED",
                                reason + "; " + why + "; fallback SKIP", s.allowReturn()));
                        continue;
                    }
                    case "ESCALATE_UP" -> {
                        usable = escalate(tenant, managerChain, levelOf(res));
                        reason = reason + "; " + why + "; fallback ESCALATE_UP";
                    }
                    case "LE_HR_ADMIN" -> {
                        usable = authorization.usersWithRoleCovering(SystemRoleCodes.HR_ADMIN, target(r), date)
                                .stream().filter(u -> !excluded.contains(u)).toList();
                        reason = reason + "; " + why + "; fallback LE_HR_ADMIN";
                    }
                    default -> {
                        // FAIL
                    }
                }
                if (usable.isEmpty()) {
                    throw ApiException.unprocessable("approver-unresolved", "Step " + s.stepKey()
                            + " has no usable approver (" + why + ") and the fallback " + onFailure + " found none");
                }
                via = "FALLBACK";
            }
            if (usable.equals(previous)) {
                out.add(new ResolvedStep(stepNo, s.stepKey(), s.nameVi(), s.nameEn(), usable, "SKIPPED",
                        reason + "; same approver as the previous step", s.allowReturn()));
                continue;
            }
            out.add(new ResolvedStep(stepNo, s.stepKey(), s.nameVi(), s.nameEn(), usable, via, reason,
                    s.allowReturn()));
            previous = usable;
        }
        return out;
    }

    private record Found(List<UUID> users, String reason) {
    }

    private Found find(UUID tenant, ApproverResolver res, String stepKey, StartRequest r, List<UUID> managerChain,
            LocalDate date) {
        Map<String, String> p = res.params();
        return switch (res.type()) {
            case LINE_MANAGER -> managerChain.isEmpty() ? new Found(List.of(), "no manager above the org unit")
                    : new Found(List.of(managerChain.getFirst()), "manager of the request's unit or nearest ancestor");
            case MANAGER_N_UP -> {
                int n = Integer.parseInt(p.get("levels"));
                yield managerChain.size() < n ? new Found(List.of(), "fewer than " + n + " managers above")
                        : new Found(List.of(managerChain.get(n - 1)), n + " level(s) up");
            }
            case ORG_UNIT_MANAGER -> new Found(manager(tenant, UUID.fromString(p.get("orgUnitId")), date)
                    .map(List::of).orElse(List.of()), "manager of unit " + p.get("orgUnitId"));
            case ROLE_IN_SCOPE -> new Found(authorization.usersWithRoleCovering(p.get("roleCode"), target(r), date),
                    "holders of " + p.get("roleCode") + " covering the request");
            case USER -> new Found(List.of(UUID.fromString(p.get("userId"))), "named user");
            case REQUESTER_PICK -> {
                UUID picked = r.pickedApprovers().get(stepKey);
                if (picked == null) {
                    yield new Found(List.of(), "the requester picked nobody");
                }
                if (p.containsKey("roleCode")
                        && !authorization.usersWithRoleCovering(p.get("roleCode"), target(r), date).contains(picked)) {
                    yield new Found(List.of(), "picked user is not a " + p.get("roleCode") + " covering the request");
                }
                yield new Found(List.of(picked), "picked by the requester");
            }
            case REQUEST_FIELD -> {
                Object v = r.conditionValues().get(p.get("field"));
                yield v == null ? new Found(List.of(), "field " + p.get("field") + " is empty")
                        : new Found(List.of(UUID.fromString(String.valueOf(v))), "request field " + p.get("field"));
            }
            case POSITION_HOLDER, FUNCTIONAL_MANAGER ->
                    new Found(List.of(), "not available in Phase 1 (positions P2, functional managers P3)");
        };
    }

    /** Distinct managers walking up from {@code orgUnitId}, skipping units without a manager or managed by excluded users. */
    private List<UUID> managerChain(UUID tenant, UUID orgUnitId, Set<UUID> excluded, LocalDate date) {
        List<UUID> chain = new ArrayList<>();
        UUID unit = orgUnitId;
        Set<UUID> seen = new LinkedHashSet<>();
        while (unit != null && seen.add(unit)) {
            record Row(UUID manager, UUID parent) {
            }
            Optional<Row> row = jdbc.sql("""
                            select manager_user_id, parent_id from core.org_unit_version
                             where tenant_id = :t and org_unit_id = :u and valid_during @> cast(:d as date)""")
                    .param("t", tenant).param("u", unit).param("d", date)
                    .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional();
            if (row.isEmpty()) {
                break;
            }
            UUID m = row.get().manager();
            if (m != null && !excluded.contains(m) && !chain.contains(m)) {
                chain.add(m);
            }
            unit = row.get().parent();
        }
        return chain;
    }

    private Optional<UUID> manager(UUID tenant, UUID orgUnitId, LocalDate date) {
        return jdbc.sql("""
                        select manager_user_id from core.org_unit_version
                         where tenant_id = :t and org_unit_id = :u and valid_during @> cast(:d as date)
                           and manager_user_id is not null""")
                .param("t", tenant).param("u", orgUnitId).param("d", date).query(UUID.class).optional();
    }

    /** The first usable manager above the step's level in the chain (line manager for non-hierarchical steps). */
    private List<UUID> escalate(UUID tenant, List<UUID> chain, int level) {
        for (int i = level; i < chain.size(); i++) {
            if (queries.mayAccess(tenant, chain.get(i))) {
                return List.of(chain.get(i));
            }
        }
        return List.of();
    }

    private static int levelOf(ApproverResolver res) {
        return switch (res.type()) {
            case LINE_MANAGER -> 1;
            case MANAGER_N_UP -> Integer.parseInt(res.params().get("levels"));
            default -> 0;
        };
    }

    private static AuthorizationTarget target(StartRequest r) {
        if (r.orgUnitId() != null) {
            return new AuthorizationTarget.OrgUnit(r.orgUnitId());
        }
        if (r.legalEntityId() != null) {
            return new AuthorizationTarget.LegalEntity(r.legalEntityId());
        }
        return AuthorizationTarget.tenant();
    }
}
