package vn.yourhrm.attendance.internal.infra;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Time &amp; Attendance's own connection pool, as login role {@code yhrm_app_att} (ADR-0021): DML only on schema
 * {@code att}, INSERT on {@code audit.audit_event}. Deliberately NOT exposed as a {@link DataSource} or
 * {@link JdbcClient} bean, so Spring Boot keeps auto-configuring Core's primary ones.
 *
 * <p>Every statement must run inside a T&amp;A transaction ({@link AttTransactional}), which first sets the tenant
 * context; without one RLS returns no rows (fail closed).
 */
public final class AttDb implements AutoCloseable {

    /** Bean name of T&amp;A's transaction manager. */
    public static final String TX = "attTransactionManager";

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    AttDb(HikariDataSource dataSource) {
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
