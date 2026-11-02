package vn.yourhrm.shared.common.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Where the current work comes from, for the audit trail (BR-CORE-090: channel, IP, user agent, correlation id).
 * Bound next to the {@link TenantContext} by the request filter (UI / API) or by a job (IMPORT, with the import job
 * id as correlation id); unbound work is audited as SYSTEM. {@link TenantAwareJpaTransactionManager} hands it to the
 * database as {@code app.channel}, {@code app.client_ip}, {@code app.user_agent} and {@code app.correlation_id}.
 *
 * <p>Nothing here identifies the tenant or the user: those come only from the validated token (ADR-0003).
 */
public record RequestOrigin(String channel, String clientIp, String userAgent, String correlationId) {

    public static final String UI = "UI";
    public static final String API = "API";
    public static final String IMPORT = "IMPORT";
    public static final String SYSTEM = "SYSTEM";

    private static final ScopedValue<RequestOrigin> CURRENT = ScopedValue.newInstance();

    public RequestOrigin {
        Objects.requireNonNull(channel, "channel");
        if (!channel.equals(UI) && !channel.equals(API) && !channel.equals(IMPORT) && !channel.equals(SYSTEM)) {
            throw new IllegalArgumentException("Unknown channel " + channel);
        }
    }

    /** Work started by a job on behalf of {@code correlationId} (e.g. an import job), keeping the caller's address. */
    public static RequestOrigin job(String channel, String correlationId) {
        RequestOrigin outer = optional().orElse(null);
        return new RequestOrigin(channel, outer == null ? null : outer.clientIp(),
                outer == null ? null : outer.userAgent(), correlationId);
    }

    public static Optional<RequestOrigin> optional() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    public static <T> T callWith(RequestOrigin origin, Callable<T> op) throws Exception {
        return ScopedValue.where(CURRENT, origin).call(op::call);
    }

    public static void runWith(RequestOrigin origin, Runnable op) {
        ScopedValue.where(CURRENT, origin).run(op);
    }
}
