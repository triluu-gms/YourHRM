package vn.yourhrm.core.internal.web;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.internal.application.MeService;
import vn.yourhrm.core.internal.application.MeService.Me;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.PublicEndpoint;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Current-user context (US-CORE-041) and self-service authorisation queries: the SPA asks "may I do X
 * on Y (as of D)?" and "what is my scope for X?". They only ever answer for the caller.
 */
@RestController
@RequestMapping("/api/v1/core/me")
class MeController {

    private final MeService me;
    private final AuthorizationService authorization;
    private final BusinessClock clock;

    MeController(MeService me, AuthorizationService authorization, BusinessClock clock) {
        this.me = me;
        this.authorization = authorization;
        this.clock = clock;
    }

    enum TargetType { TENANT, LEGAL_ENTITY, ORG_UNIT, LOCATION, EMPLOYEE }

    record CheckResult(String permission, TargetType targetType, UUID targetId, LocalDate asOf, boolean allowed) {
    }

    record ScopeResult(String permission, LocalDate asOf, ScopePredicate scope) {
    }

    @GetMapping
    @PublicEndpoint(reason = "Every authenticated tenant user may read their own context")
    public Me get() {
        return me.me();
    }

    @GetMapping("/authorisation/check")
    @PublicEndpoint(reason = "Answers only for the caller's own permissions")
    public CheckResult check(@RequestParam("permission") String permission,
            @RequestParam(name = "targetType", defaultValue = "TENANT") TargetType targetType,
            @RequestParam(name = "targetId", required = false) UUID targetId,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "orgUnitId", required = false) UUID orgUnitId,
            @RequestParam(name = "locationId", required = false) UUID locationId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        if (targetType != TargetType.TENANT && targetId == null) {
            throw ApiException.unprocessable("target-id-required", "targetId is required for " + targetType);
        }
        AuthorizationTarget target = switch (targetType) {
            case TENANT -> AuthorizationTarget.tenant();
            case LEGAL_ENTITY -> new AuthorizationTarget.LegalEntity(targetId);
            case ORG_UNIT -> new AuthorizationTarget.OrgUnit(targetId);
            case LOCATION -> new AuthorizationTarget.Location(targetId);
            case EMPLOYEE -> new AuthorizationTarget.Employee(targetId, legalEntityId, orgUnitId, locationId);
        };
        return new CheckResult(permission, targetType, targetId, date, authorization.can(permission, target, date));
    }

    @GetMapping("/authorisation/scope")
    @PublicEndpoint(reason = "Answers only for the caller's own permissions")
    public ScopeResult scope(@RequestParam("permission") String permission,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        return new ScopeResult(permission, date, authorization.scopeFor(permission, date));
    }
}
