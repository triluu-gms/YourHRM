package vn.yourhrm.attendance.internal.infra;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import vn.yourhrm.shared.common.tenancy.TenantAwareDataSourceTransactionManager;

/**
 * T&amp;A's pool and transaction manager (ADR-0021). Properties, all optional:
 * {@code yhrm.attendance.datasource.url} (default: {@code spring.datasource.url}, same database),
 * {@code .username} (default {@code yhrm_app_att}), {@code .password}, {@code .maximum-pool-size} (default 10).
 */
@Configuration(proxyBeanMethods = false)
class AttendanceDataSourceConfiguration {

    @Bean(destroyMethod = "close")
    AttDb attDb(Environment env) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("yhrm-att");
        ds.setJdbcUrl(env.getProperty("yhrm.attendance.datasource.url",
                env.getRequiredProperty("spring.datasource.url")));
        ds.setUsername(env.getProperty("yhrm.attendance.datasource.username", "yhrm_app_att"));
        ds.setPassword(env.getProperty("yhrm.attendance.datasource.password", ""));
        ds.setMaximumPoolSize(env.getProperty("yhrm.attendance.datasource.maximum-pool-size", Integer.class, 10));
        // No session-level SET: the tenant context is set per TRANSACTION by the transaction manager.
        return new AttDb(ds);
    }

    @Bean(name = AttDb.TX)
    PlatformTransactionManager attTransactionManager(AttDb db) {
        return new TenantAwareDataSourceTransactionManager(db.dataSource());
    }
}
