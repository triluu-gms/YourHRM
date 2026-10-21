package vn.yourhrm.shared.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import vn.yourhrm.shared.auth.api.PublicEndpoint;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Runtime half of the US-CORE-039 guardrail (the build-time half is an ArchUnit rule): an API handler
 * that declares neither {@link RequiresPermission} nor {@link PublicEndpoint} is refused with 403
 * instead of being silently open.
 */
final class EndpointDeclarationGuard implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod hm && !isDeclared(hm)) {
            throw new AccessDeniedException("Endpoint declares no permission (@RequiresPermission / @PublicEndpoint)");
        }
        return true;
    }

    static boolean isDeclared(HandlerMethod hm) {
        return hm.hasMethodAnnotation(RequiresPermission.class)
                || hm.hasMethodAnnotation(PublicEndpoint.class)
                || AnnotatedElementUtils.hasAnnotation(hm.getBeanType(), RequiresPermission.class);
    }
}
