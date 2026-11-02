package vn.yourhrm.core.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import vn.yourhrm.core.internal.application.WorkflowEngine.ClientInfo;

/**
 * Reads the client's address and device (User-Agent) for the approval trail (BR-CORE-061: "IP / device"). The only
 * class besides the {@code TenantContextFilter} allowed to read request headers (ArchitectureTest): it reads
 * nothing that identifies the tenant or the user, which come only from the validated JWT (ADR-0003).
 */
final class ClientInfoResolver {

    private static final int MAX_USER_AGENT = 512;

    private ClientInfoResolver() {
    }

    static ClientInfo web(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT);
        }
        return new ClientInfo(request.getRemoteAddr(), userAgent, "WEB");
    }
}
