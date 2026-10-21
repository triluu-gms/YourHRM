package vn.yourhrm.shared.common.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Replaces Spring Boot's default JPA transaction manager with the tenant-aware one, so that EVERY
 * transaction (JPA repositories, {@code @Transactional} services, {@code TransactionTemplate}) starts with
 * the {@code set_config(...)} call (DESIGN.md §3.2).
 */
@Configuration(proxyBeanMethods = false)
public class TenancyPersistenceConfiguration {

    @Bean(name = "transactionManager")
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareJpaTransactionManager(emf);
    }
}
