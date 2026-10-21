package vn.yourhrm.shared.auth.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Coarse permission check (ADR-0006 decision 2), enforced by Spring method security before the method
 * runs: the current user must hold {@link #value()} through at least one role assignment that is valid
 * today. Denial = HTTP 403 ProblemDetail.
 *
 * <p>Row-level data scope is NOT decided here: services call {@link AuthorizationService#can} or apply
 * {@link AuthorizationService#scopeFor} to their queries.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    /** Permission code, e.g. {@code core.role.view}. */
    String value();

    /**
     * When {@code true} the permission must be granted with scope {@code TENANT} (tenant administration
     * functions such as role and user management, which have no narrower data scope).
     */
    boolean tenantWide() default false;
}
