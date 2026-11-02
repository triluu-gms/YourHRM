package vn.yourhrm.core.internal.application;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.internal.infra.AuditWriter;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.RequestOrigin;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/** The audit client of every module (BR-CORE-090/091, P-20 / P-27). */
@Service
public class AuditLogService implements AuditLog {

    static final Set<String> MODULES = Set.of("saas", "core", "hr", "att", "pay", "rec");
    /** Object type of security events (BR-CORE-091); the audit search groups them under "security". */
    public static final String SECURITY_OBJECT = "security";
    private static final Set<String> ROW_ACTIONS = Set.of("INSERT", "UPDATE", "DELETE");
    private static final Pattern ACTION = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final int MAX_DETAIL = 1000;

    private final AuditWriter writer;
    private final TransactionTemplate ownTx;

    public AuditLogService(AuditWriter writer, PlatformTransactionManager txManager) {
        this.writer = writer;
        this.ownTx = new TransactionTemplate(txManager);
        this.ownTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public void record(BusinessAction a) {
        if (!ACTION.matcher(a.action()).matches() || ROW_ACTIONS.contains(a.action())) {
            throw ApiException.unprocessable("invalid-audit-action",
                    "Audit action " + a.action() + " is not an upper-case business verb (data changes are row-audited)");
        }
        if (!MODULES.contains(a.module())) {
            throw ApiException.unprocessable("invalid-audit-module", "Unknown module " + a.module());
        }
        writer.append(AuditWriter.Event.ofCurrentActor(a.action(), a.module(), a.objectType(), a.objectId(),
                a.legalEntityId(), capped(a.details()), false));
    }

    @Override
    public void recordSecurityEvent(SecurityEvent e) {
        TenantContext bound = TenantContext.optional().orElse(null);
        String requestId = bound != null && bound.tenantId().equals(e.tenantId()) ? bound.requestId() : null;
        TenantContext ctx = new TenantContext(e.tenantId(), e.userAccountId(), e.identityId(), requestId, null);
        RequestOrigin outer = RequestOrigin.optional().orElse(null);
        RequestOrigin origin = new RequestOrigin(outer == null ? RequestOrigin.UI : outer.channel(),
                e.clientIp() != null ? e.clientIp() : outer == null ? null : outer.clientIp(),
                e.userAgent() != null ? e.userAgent() : outer == null ? null : outer.userAgent(),
                outer == null ? null : outer.correlationId());
        Map<String, String> details = new HashMap<>(capped(e.details()));
        if (e.identityId() != null) {
            details.put("identity", e.identityId());
        }
        String actorType = e.operatorId() != null ? "OPERATOR"
                : e.userAccountId() != null || e.identityId() != null ? "USER" : "SYSTEM";
        try {
            TenantContext.callWith(ctx, () -> RequestOrigin.callWith(origin, () -> ownTx.execute(s -> {
                writer.append(new AuditWriter.Event(e.tenantId(), actorType, e.userAccountId(), e.operatorId(), null,
                        e.type().name(), "core", SECURITY_OBJECT, null, null, details,
                        e.type() == SecurityEventType.SENSITIVE_FIELD_VIEWED));
                return null;
            })));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, String> capped(Map<String, String> details) {
        Map<String, String> out = new HashMap<>();
        details.forEach((k, v) -> out.put(k, v != null && v.length() > MAX_DETAIL ? v.substring(0, MAX_DETAIL) : v));
        return out;
    }
}
