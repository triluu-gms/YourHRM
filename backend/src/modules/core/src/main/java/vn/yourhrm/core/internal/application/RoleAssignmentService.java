package vn.yourhrm.core.internal.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CoreEvents.RoleAssignmentChanged;
import vn.yourhrm.core.internal.domain.Role;
import vn.yourhrm.core.internal.domain.RoleAssignment;
import vn.yourhrm.core.internal.infra.MasterDataQueries;
import vn.yourhrm.core.internal.infra.RoleAssignmentRepository;
import vn.yourhrm.core.internal.infra.RoleRepository;
import vn.yourhrm.core.internal.infra.UserAccountRepository;
import vn.yourhrm.shared.auth.api.ScopeType;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Role assignments with data scope and validity (US-CORE-035). Scope ids are validated against Core master
 * data of the same tenant. Every change evicts the user's authorisation cache after commit.
 */
@Service
public class RoleAssignmentService {

    private final RoleAssignmentRepository assignments;
    private final RoleRepository roles;
    private final UserAccountRepository users;
    private final MasterDataQueries masterData;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;

    public RoleAssignmentService(RoleAssignmentRepository assignments, RoleRepository roles,
            UserAccountRepository users, MasterDataQueries masterData, ApplicationEventPublisher events,
            BusinessClock clock) {
        this.assignments = assignments;
        this.roles = roles;
        this.users = users;
        this.masterData = masterData;
        this.events = events;
        this.clock = clock;
    }

    public record AssignmentView(UUID id, UUID userId, UUID roleId, String roleCode, ScopeType scopeType,
            Set<UUID> scopeIds, boolean includeSubtree, LocalDate validFrom, LocalDate validTo, boolean activeToday,
            UUID grantedBy, Instant createdAt) {
    }

    public record GrantCommand(UUID roleId, ScopeType scopeType, Set<UUID> scopeIds, Boolean includeSubtree,
            LocalDate validFrom, LocalDate validTo) {
    }

    @Transactional(readOnly = true)
    public List<AssignmentView> list(UUID userId, boolean includeHistory) {
        UUID tenant = TenantContext.current().tenantId();
        requireUser(tenant, userId);
        LocalDate today = clock.today();
        return assignments.findByUser(tenant, userId).stream()
                .filter(a -> includeHistory || a.getValidTo() == null || a.getValidTo().isAfter(today))
                .map(a -> view(a, today))
                .toList();
    }

    @Transactional
    public AssignmentView grant(UUID userId, GrantCommand cmd) {
        TenantContext ctx = TenantContext.current();
        UUID tenant = ctx.tenantId();
        requireUser(tenant, userId);
        Role role = roles.findByTenantIdAndId(tenant, cmd.roleId())
                .orElseThrow(() -> ApiException.unprocessable("unknown-role", "Role " + cmd.roleId() + " not found"));
        Set<UUID> ids = cmd.scopeIds() == null ? Set.of() : new LinkedHashSet<>(cmd.scopeIds());
        if (cmd.scopeType().requiresIds() && ids.isEmpty()) {
            throw ApiException.unprocessable("scope-ids-required", "scopeIds are required for scope " + cmd.scopeType());
        }
        if (!cmd.scopeType().requiresIds() && !ids.isEmpty()) {
            throw ApiException.unprocessable("scope-ids-not-allowed", "scopeIds must be empty for scope " + cmd.scopeType());
        }
        if (cmd.scopeType().requiresIds()) {
            Set<UUID> missing = masterData.missingScopeIds(tenant, cmd.scopeType(), ids);
            if (!missing.isEmpty()) {
                throw ApiException.unprocessable("unknown-scope-ids",
                        "Unknown " + cmd.scopeType() + " id(s): " + missing, Map.of("scopeIds", List.copyOf(missing)));
            }
        }
        LocalDate validFrom = cmd.validFrom() == null ? clock.today() : cmd.validFrom();
        if (cmd.validTo() != null && !cmd.validTo().isAfter(validFrom)) {
            throw ApiException.unprocessable("invalid-period", "validTo must be after validFrom (end is exclusive)");
        }
        boolean subtree = cmd.includeSubtree() == null || cmd.includeSubtree();
        RoleAssignment a = RoleAssignment.grant(tenant, Ids.newId(), userId, role.getId(), cmd.scopeType(), ids,
                subtree, validFrom, cmd.validTo(), ctx.userAccountId(), clock.now());
        assignments.saveAndFlush(a);
        events.publishEvent(new RoleAssignmentChanged(tenant, userId, a.getAssignmentId(), "GRANTED"));
        return view(a, clock.today(), role.getCode());
    }

    /**
     * Revoke: an assignment that already took effect is closed at the start of today (exclusive end), so
     * the history stays; one that has not started yet is deleted.
     */
    @Transactional
    public void revoke(UUID userId, UUID assignmentId) {
        UUID tenant = TenantContext.current().tenantId();
        RoleAssignment a = assignments.findOne(tenant, assignmentId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Role assignment", assignmentId));
        LocalDate today = clock.today();
        if (a.startsOnOrAfter(today)) {
            assignments.delete(a);
        } else {
            a.endBefore(today);
            assignments.save(a);
        }
        assignments.flush();
        events.publishEvent(new RoleAssignmentChanged(tenant, userId, assignmentId, "REVOKED"));
    }

    private void requireUser(UUID tenant, UUID userId) {
        if (users.findByTenantIdAndId(tenant, userId).isEmpty()) {
            throw ApiException.notFound("User", userId);
        }
    }

    private AssignmentView view(RoleAssignment a, LocalDate today) {
        String code = roles.findByTenantIdAndId(a.getTenantId(), a.getRoleId()).map(Role::getCode).orElse(null);
        return view(a, today, code);
    }

    private static AssignmentView view(RoleAssignment a, LocalDate today, String roleCode) {
        return new AssignmentView(a.getAssignmentId(), a.getUserId(), a.getRoleId(), roleCode, a.getScopeType(),
                a.getScopeIds(), a.isIncludeSubtree(), a.getValidFrom(), a.getValidTo(), a.isActiveOn(today),
                a.getGrantedBy(), a.getCreatedAt());
    }
}
