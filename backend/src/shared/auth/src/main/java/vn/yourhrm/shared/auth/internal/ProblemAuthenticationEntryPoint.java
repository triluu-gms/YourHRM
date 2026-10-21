package vn.yourhrm.shared.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

/** 401 as ProblemDetail, keeping the standard {@code WWW-Authenticate: Bearer ...} header. */
final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        delegate.commence(request, response, ex);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"urn:yourhrm:problem:unauthenticated\",\"title\":\"Unauthorized\","
                + "\"status\":401,\"detail\":\"A valid bearer token is required\",\"code\":\"unauthenticated\"}");
    }
}
