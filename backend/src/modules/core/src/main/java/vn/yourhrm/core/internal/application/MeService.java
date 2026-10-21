package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.internal.domain.UserAccount;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation;
import vn.yourhrm.core.internal.domain.authz.EffectiveAuthorisation.AssignmentGrant;
import vn.yourhrm.core.internal.infra.MasterDataQueries;
import vn.yourhrm.core.internal.infra.MasterDataQueries.TenantInfo;
import vn.yourhrm.core.internal.infra.UserAccountRepository;
import vn.yourhrm.shared.auth.api.ScopeType;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/** Current-user context for the SPA (US-CORE-041): user, tenant, locale, roles, effective permissions + scopes. */
@Service
public class MeService {

    private final UserAccountRepository users;
    private final MasterDataQueries masterData;
    private final CoreAuthorizationService authorization;
    private final BusinessClock clock;

    public MeService(UserAccountRepository users, MasterDataQueries masterData,
            CoreAuthorizationService authorization, BusinessClock clock) {
        this.users = users;
        this.masterData = masterData;
        this.authorization = authorization;
        this.clock = clock;
    }

    public record Me(UserInfo user, TenantInfo tenant, String locale, LocalDate asOf, List<RoleGrant> roles,
            List<EffectivePermission> permissions) {
    }

    public record UserInfo(UUID id, String login, String email, String status, UUID employmentId, UUID identityId) {
    }

    public record RoleGrant(UUID assignmentId, UUID roleId, String code, String nameVi, String nameEn,
            ScopeType scopeType, Set<UUID> scopeIds, boolean includeSubtree, LocalDate validFrom, LocalDate validTo) {
    }

    public record EffectivePermission(String code, List<Scope> scopes) {
    }

    public record Scope(ScopeType scopeType, Set<UUID> scopeIds, boolean includeSubtree, String fieldMaskLevel) {
    }

    @Transactional(readOnly = true)
    public Me me() {
        TenantContext ctx = TenantContext.current();
        UserAccount u = users.findByTenantIdAndId(ctx.tenantId(), ctx.userAccountId())
                .orElseThrow(() -> ApiException.notFound("User", ctx.userAccountId()));
        TenantInfo tenant = masterData.tenant(ctx.tenantId())
                .orElse(new TenantInfo(ctx.tenantId(), null, null, null, null, null));
        LocalDate today = clock.today();
        EffectiveAuthorisation snapshot = authorization.currentSnapshot();
        List<RoleGrant> roles = new ArrayList<>();
        Map<String, List<Scope>> perms = new TreeMap<>();
        snapshot.activeOn(today).forEach(g -> {
            roles.add(roleGrant(g));
            g.permissions().forEach((code, mask) -> perms.computeIfAbsent(code, k -> new ArrayList<>())
                    .add(new Scope(g.scopeType(), g.scopeIds(), g.includeSubtree(), mask.name())));
        });
        List<EffectivePermission> permissions = perms.entrySet().stream()
                .map(e -> new EffectivePermission(e.getKey(), e.getValue())).toList();
        String locale = u.getLocale() != null ? u.getLocale() : tenant.defaultLocale();
        return new Me(new UserInfo(u.getId(), u.getLogin(), u.getEmail(), u.getStatus().name(), u.getEmploymentId(),
                u.getIdentityId()), tenant, locale, today, roles, permissions);
    }

    private static RoleGrant roleGrant(AssignmentGrant g) {
        return new RoleGrant(g.assignmentId(), g.roleId(), g.roleCode(), g.roleNameVi(), g.roleNameEn(), g.scopeType(),
                g.scopeIds(), g.includeSubtree(), g.validFrom(), g.validTo());
    }
}
