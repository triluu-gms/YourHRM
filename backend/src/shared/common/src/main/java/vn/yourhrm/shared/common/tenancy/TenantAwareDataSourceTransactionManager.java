package vn.yourhrm.shared.common.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * JDBC transaction manager of a module's own connection pool (ADR-0021: one Hikari pool and login role per module,
 * e.g. {@code yhrm_app_hr}). Like {@link TenantAwareJpaTransactionManager}, the first statement of every transaction,
 * read-only ones included, is the {@code set_config(...)} call of {@link TenantAwareJpaTransactionManager#SET_CONTEXT_SQL}
 * with the bound {@link TenantContext} (or {@link PlatformContext}); without either the transaction is refused.
 *
 * <p>A module transaction never shares the physical transaction of another module's pool: cross-module calls in one
 * request commit separately (ADR-0021 consequences; consistency through outbox and sagas, ADR-0019).
 */
public class TenantAwareDataSourceTransactionManager extends DataSourceTransactionManager {

    private static final long serialVersionUID = 1L;

    /** Settings resolved by {@link #doBegin} for the connection {@link #prepareTransactionalConnection} prepares. */
    private static final ThreadLocal<String[]> PENDING = new ThreadLocal<>();

    public TenantAwareDataSourceTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // Decide BEFORE opening anything, so that a missing context never leaves a half-open transaction.
        PENDING.set(TenantAwareJpaTransactionManager.resolveSettings());
        try {
            super.doBegin(transaction, definition);
        } finally {
            PENDING.remove();
        }
    }

    /**
     * Runs inside {@code doBegin} right after auto-commit was switched off on the new transaction's connection; a
     * failure here makes {@code doBegin} release the connection and throw {@code CannotCreateTransactionException}.
     */
    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        TenantAwareJpaTransactionManager.applySettings(con, PENDING.get());
    }
}
