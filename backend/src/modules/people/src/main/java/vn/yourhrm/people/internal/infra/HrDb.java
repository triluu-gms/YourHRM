package vn.yourhrm.people.internal.infra;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * People's own connection pool, as login role {@code yhrm_app_hr} (ADR-0021): DML only on schema {@code hr}, SELECT on
 * Core's global reference tables, INSERT on {@code audit.audit_event} and {@code saas.job}. Deliberately NOT exposed as
 * a {@link DataSource} or {@link JdbcClient} bean, so Spring Boot keeps auto-configuring Core's primary ones.
 *
 * <p>Every statement must run inside a People transaction ({@link HrTransactional}), which first sets the tenant
 * context; without one RLS returns no rows (fail closed).
 */
public final class HrDb implements AutoCloseable {

    /** Bean name of People's transaction manager. */
    public static final String TX = "hrTransactionManager";

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    HrDb(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
