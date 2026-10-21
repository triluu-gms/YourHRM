package vn.yourhrm.shared.auth.internal;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Method-security decision for {@link RequiresPermission} (ADR-0006 decision 2): delegates to the single
 * {@link AuthorizationService}. Denies when no tenant user is bound (fail closed).
 */
final class RequiresPermissionAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    private final ObjectProvider<AuthorizationService> authorizationService;

    RequiresPermissionAuthorizationManager(ObjectProvider<AuthorizationService> authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, MethodInvocation invocation) {
        RequiresPermission rp = findAnnotation(invocation);
        if (rp == null) {
            return new AuthorizationDecision(false);
        }
        var ctx = TenantContext.optional();
        if (ctx.isEmpty() || ctx.get().userAccountId() == null) {
            return new AuthorizationDecision(false);
        }
        AuthorizationService service = authorizationService.getObject();
        boolean granted = rp.tenantWide()
                ? service.hasTenantWidePermission(rp.value())
                : service.hasPermission(rp.value());
        return new AuthorizationDecision(granted);
    }

    static RequiresPermission findAnnotation(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        Class<?> targetClass = invocation.getThis() == null ? method.getDeclaringClass()
                : AopUtils.getTargetClass(invocation.getThis());
        Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
        RequiresPermission rp = AnnotatedElementUtils.findMergedAnnotation(specific, RequiresPermission.class);
        if (rp == null) {
            rp = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequiresPermission.class);
        }
        return rp;
    }
}
