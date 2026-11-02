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
import vn.yourhrm.shared.common.tenancy.RequestOrigin;
import vn.yourhrm.shared.common.tenancy.SupportSession;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Binds the {@link TenantContext} for an authenticated request (ADR-0003 decision 1-2, US-CORE-041).
 *
 * <p>Runs right after bearer-token authentication. The tenant comes ONLY from the validated JWT
 * (claim {@code tenant_id}, falling back to {@code tid} as named in ADR-0003/0005) and the identity from
 * {@code sub}; request headers and bodies are never consulted for them. The identity is mapped to the
 * tenant user account through Core ({@link UserAccountResolver}); unknown or deactivated users get 403.
 * The rest of the filter chain and the handler run inside the {@link ScopedValue} binding, together with the
 * {@link RequestOrigin} of the request for the audit trail (BR-CORE-090: client address, user agent, correlation id).
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_CLAIM = "tenant_id";
    public static final String TENANT_CLAIM_ADR = "tid";
    /** Claims of a platform support operator token (BR-CORE-041). */
    public static final String OPERATOR_CLAIM = "operator_id";
    public static final String SUPPORT_GRANT_CLAIM = "support_grant_id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final int MAX_USER_AGENT = 512;
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
        if (jwt.hasClaim(OPERATOR_CLAIM) || jwt.hasClaim(SUPPORT_GRANT_CLAIM)) {
            supportSession(request, response, chain, jwt, tenantId.get());
            return;
        }
        Optional<UUID> identityId = parseUuid(jwt.getSubject());
        if (identityId.isEmpty()) {
            writeProblem(response, "invalid-subject", "The access token subject is not a valid identity id");
            return;
        }
        String requestId = requestId(request);

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
            RequestOrigin origin = origin(request, requestId);
            TenantContext.callWith(ctx, () -> RequestOrigin.callWith(origin, () -> {
                chain.doFilter(request, response);
                return null;
            }));
        } catch (IOException | ServletException | RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServletException(ex);
        } finally {
            MDC.remove("tenant_id");
            MDC.remove("request_id");
        }
    }

    /**
     * A platform operator acting under a tenant-approved support grant (BR-CORE-041): no tenant user account; Core
     * validates the grant and logs the request (refusals too) before anything else runs.
     */
    private void supportSession(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Jwt jwt,
            UUID tenantId) throws IOException, ServletException {
        Optional<UUID> operatorId = parseUuid(jwt.getClaimAsString(OPERATOR_CLAIM));
        Optional<UUID> grantId = parseUuid(jwt.getClaimAsString(SUPPORT_GRANT_CLAIM));
        String requestId = requestId(request);
        TenantContext ctx = new TenantContext(tenantId, null, jwt.getSubject(), requestId, null);
        try {
            Optional<SupportSession> session = operatorId.isEmpty() || grantId.isEmpty() ? Optional.empty()
                    : TenantContext.callWith(ctx, () -> userAccountResolver.resolveSupport(tenantId, operatorId.get(),
                            grantId.get(), request.getMethod(), request.getRequestURI()));
            if (session.isEmpty()) {
                writeProblem(response, "support-grant-invalid",
                        "No valid, tenant-approved support grant covers this operator access");
                return;
            }
            MDC.put("tenant_id", tenantId.toString());
            MDC.put("request_id", requestId);
            response.setHeader("X-Request-Id", requestId);
            RequestOrigin origin = origin(request, requestId);
            TenantContext.callWith(ctx, () -> SupportSession.callWith(session.get(), () -> RequestOrigin.callWith(
                    origin, () -> {
                        chain.doFilter(request, response);
                        return null;
                    })));
        } catch (IOException | ServletException | RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServletException(ex);
        } finally {
            MDC.remove("tenant_id");
            MDC.remove("request_id");
        }
    }

    private static String requestId(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader("X-Request-Id"))
                .filter(h -> SAFE_REQUEST_ID.matcher(h).matches())
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    /**
     * Interactive users sign in through the web / mobile UI; machine API clients (client-credentials tokens) are a
     * Phase 2 SaaS feature, so every request accepted here is channel UI. The correlation id of a call chain comes
     * from {@code X-Correlation-Id} when it is well-formed, otherwise it is the request id.
     */
    private static RequestOrigin origin(HttpServletRequest request, String requestId) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT);
        }
        String correlationId = Optional.ofNullable(request.getHeader("X-Correlation-Id"))
                .filter(h -> SAFE_REQUEST_ID.matcher(h).matches()).orElse(requestId);
        return new RequestOrigin(RequestOrigin.UI, request.getRemoteAddr(), userAgent, correlationId);
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
