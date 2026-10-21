package vn.yourhrm.shared.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.yourhrm.shared.auth.api.spi.UserAccountResolver;
import vn.yourhrm.shared.auth.api.spi.UserAccountResolver.ResolvedUser;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Binds the {@link TenantContext} for an authenticated request (ADR-0003 decision 1-2, US-CORE-041).
 *
 * <p>Runs right after bearer-token authentication. The tenant comes ONLY from the validated JWT
 * (claim {@code tenant_id}, falling back to {@code tid} as named in ADR-0003/0005) and the identity from
 * {@code sub}; request headers and bodies are never consulted for them. The identity is mapped to the
 * tenant user account through Core ({@link UserAccountResolver}); unknown or deactivated users get 403.
 * The rest of the filter chain and the handler run inside the {@link ScopedValue} binding.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_CLAIM = "tenant_id";
    public static final String TENANT_CLAIM_ADR = "tid";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    private final UserAccountResolver userAccountResolver;

    public TenantContextFilter(UserAccountResolver userAccountResolver) {
        this.userAccountResolver = userAccountResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            chain.doFilter(request, response); // anonymous: the authorization filter answers 401 where needed
            return;
        }
        Jwt jwt = jwtAuth.getToken();
        Optional<UUID> tenantId = parseUuid(Optional.ofNullable(jwt.getClaimAsString(TENANT_CLAIM))
                .orElse(jwt.getClaimAsString(TENANT_CLAIM_ADR)));
        if (tenantId.isEmpty()) {
            writeProblem(response, "no-tenant", "The access token carries no valid tenant claim");
            return;
        }
        Optional<UUID> identityId = parseUuid(jwt.getSubject());
        if (identityId.isEmpty()) {
            writeProblem(response, "invalid-subject", "The access token subject is not a valid identity id");
            return;
        }
        String requestId = Optional.ofNullable(request.getHeader("X-Request-Id"))
                .filter(h -> SAFE_REQUEST_ID.matcher(h).matches())
                .orElseGet(() -> UUID.randomUUID().toString());

        TenantContext provisional = new TenantContext(tenantId.get(), null, jwt.getSubject(), requestId, null);
        try {
            Optional<ResolvedUser> user = TenantContext.callWith(provisional,
                    () -> userAccountResolver.resolve(tenantId.get(), identityId.get()));
            if (user.isEmpty()) {
                writeProblem(response, "unknown-user", "No user account of this tenant is linked to the identity");
                return;
            }
            if (!user.get().mayAccess()) {
                writeProblem(response, "user-inactive", "The user account is not active");
                return;
            }
            TenantContext ctx = provisional.withUser(user.get().userAccountId(), user.get().locale());
            MDC.put("tenant_id", ctx.tenantId().toString());
            MDC.put("request_id", requestId);
            response.setHeader("X-Request-Id", requestId);
            TenantContext.callWith(ctx, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServletException(ex);
        } finally {
            MDC.remove("tenant_id");
            MDC.remove("request_id");
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    static void writeProblem(HttpServletResponse response, String code, String detail) throws IOException {
        log.debug("Request rejected: {} ({})", code, detail);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"urn:yourhrm:problem:" + code + "\",\"title\":\"Forbidden\","
                + "\"status\":403,\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }
}
