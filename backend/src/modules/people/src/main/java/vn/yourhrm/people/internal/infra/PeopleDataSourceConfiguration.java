package vn.yourhrm.people.internal.infra;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import vn.yourhrm.shared.common.tenancy.TenantAwareDataSourceTransactionManager;

/**
 * People's pool and transaction manager (ADR-0021). Properties, all optional:
 * {@code yhrm.people.datasource.url} (default: {@code spring.datasource.url}, same database),
 * {@code .username} (default {@code yhrm_app_hr}), {@code .password}, {@code .maximum-pool-size} (default 10).
 */
@Configuration(proxyBeanMethods = false)
class PeopleDataSourceConfiguration {

    @Bean(destroyMethod = "close")
    HrDb hrDb(Environment env) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("yhrm-hr");
        ds.setJdbcUrl(env.getProperty("yhrm.people.datasource.url", env.getRequiredProperty("spring.datasource.url")));
        ds.setUsername(env.getProperty("yhrm.people.datasource.username", "yhrm_app_hr"));
        ds.setPassword(env.getProperty("yhrm.people.datasource.password", ""));
        ds.setMaximumPoolSize(env.getProperty("yhrm.people.datasource.maximum-pool-size", Integer.class, 10));
        // No session-level SET: the tenant context is set per TRANSACTION by the transaction manager.
        return new HrDb(ds);
    }

    @Bean(name = HrDb.TX)
    PlatformTransactionManager hrTransactionManager(HrDb db) {
        return new TenantAwareDataSourceTransactionManager(db.dataSource());
    }
}
