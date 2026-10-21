package vn.yourhrm.shared.auth.internal;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.yourhrm.shared.common.error.ProblemDetailsExceptionHandler;

/** Permission denials (method security, endpoint guard) as 403 ProblemDetail. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessDeniedProblemHandler {

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handle(AccessDeniedException ex) {
        return ProblemDetailsExceptionHandler.problem(HttpStatus.FORBIDDEN, "forbidden",
                "You do not have the permission required for this operation");
    }
}
