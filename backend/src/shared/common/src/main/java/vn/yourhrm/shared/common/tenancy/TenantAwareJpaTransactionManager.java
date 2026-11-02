package vn.yourhrm.shared.common.tenancy;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JPA transaction manager that makes the tenant context part of every physical transaction
 * (DESIGN.md §3.2, ADR-0003, architecture-overview §5.2).
 *
 * <p>Right after {@code BEGIN} (Hibernate switches auto-commit off), and before any other statement of the
 * transaction, it issues on the transaction's JDBC connection:
 *
 * <pre>
 * select set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true),
 *        set_config('app.request_id', ?, true), set_config('app.purpose', ?, true),
 *        set_config('app.operator_id', ?, true), set_config('app.support_grant_id', ?, true),
 *        set_config('app.channel', ?, true), set_config('app.client_ip', ?, true),
 *        set_config('app.user_agent', ?, true), set_config('app.correlation_id', ?, true)
 * </pre>
 *
 * <p>The operator and grant are set only in a platform support session ({@link SupportSession}, BR-CORE-041) and let
 * the row audit attribute changes to the operator and the support grant. The last four come from the bound
 * {@link RequestOrigin} (BR-CORE-090); without one the audit records the channel SYSTEM.
 *
 * <p>{@code is_local = true} makes the values transaction-local ({@code SET LOCAL}); they vanish at
 * commit/rollback, so a pooled connection never carries a tenant into the next transaction (PgBouncer
 * transaction pooling safe). Applies to read-only transactions too. JDBC access through
 * {@code JdbcClient}/{@code JdbcTemplate} inside the transaction uses the same connection (the JPA dialect
 * exposes it), so JPA and JDBC see the same context.
 *
 * <ul>
 *   <li>No {@link TenantContext} and no {@link PlatformContext}: the transaction is refused (fail fast).</li>
 *   <li>{@link PlatformContext}: the tenant settings are explicitly set to '' so RLS hides all tenant rows.</li>
 * </ul>
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public static final String SET_CONTEXT_SQL =
            "select set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true), "
                    + "set_config('app.request_id', ?, true), set_config('app.purpose', ?, true), "
                    + "set_config('app.operator_id', ?, true), set_config('app.support_grant_id', ?, true), "
                    + "set_config('app.channel', ?, true), set_config('app.client_ip', ?, true), "
                    + "set_config('app.user_agent', ?, true), set_config('app.correlation_id', ?, true)";

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(TenantAwareJpaTransactionManager.class);

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // Decide BEFORE opening anything, so that a missing context never leaves a half-open transaction.
        String[] settings = resolveSettings();
        super.doBegin(transaction, definition);
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        try {
            holder.getEntityManager().unwrap(Session.class).doWork(connection -> applySettings(connection, settings));
        } catch (RuntimeException ex) {
            log.error("Could not apply the tenant context to the new transaction; rolling back", ex);
            try {
                holder.getEntityManager().getTransaction().rollback();
            } catch (RuntimeException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            doCleanupAfterCompletion(transaction);
            throw new CannotCreateTransactionException("Could not set the tenant context on the transaction", ex);
        }
    }

    static String[] resolveSettings() {
        var ctx = TenantContext.optional();
        RequestOrigin origin = RequestOrigin.optional().orElse(null);
        String[] originSettings = origin == null ? new String[] {RequestOrigin.SYSTEM, "", "", ""}
                : new String[] {origin.channel(), nullToEmpty(origin.clientIp()), nullToEmpty(origin.userAgent()),
                    nullToEmpty(origin.correlationId())};
        if (ctx.isPresent()) {
            TenantContext c = ctx.get();
            var support = SupportSession.optional();
            return new String[] {
                c.tenantId().toString(),
                c.userAccountId() == null ? "" : c.userAccountId().toString(),
                c.requestId() == null ? "" : c.requestId(),
                support.isPresent() ? "SUPPORT" : "",
                support.map(x -> x.operatorId().toString()).orElse(""),
                support.map(x -> x.supportGrantId().toString()).orElse(""),
                originSettings[0], originSettings[1], originSettings[2], originSettings[3]
            };
        }
        if (PlatformContext.isBound()) {
            return new String[] {"", "", "", PlatformContext.purpose(), "", "",
                originSettings[0], originSettings[1], originSettings[2], originSettings[3]};
        }
        throw new NoTenantContextException();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static void applySettings(Connection connection, String[] settings) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SET_CONTEXT_SQL)) {
            for (int i = 0; i < settings.length; i++) {
                ps.setString(i + 1, settings[i]);
            }
            ps.execute();
        }
    }
}
