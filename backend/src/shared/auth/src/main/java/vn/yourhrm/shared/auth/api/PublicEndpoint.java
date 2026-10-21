package vn.yourhrm.shared.auth.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that needs NO permission (US-CORE-039 guardrail: every handler carries either
 * {@link RequiresPermission} or this annotation). The caller is still authenticated by the security filter
 * chain for {@code /api/**} (valid JWT + known, active tenant user); "public" means "no permission check",
 * e.g. {@code GET /me}. A reason is mandatory so that reviewers see why the check is skipped.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface PublicEndpoint {

    /** Why no permission is required. */
    String reason();
}
