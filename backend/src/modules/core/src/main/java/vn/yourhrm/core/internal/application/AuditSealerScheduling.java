package vn.yourhrm.core.internal.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Runs the {@link AuditSealer} (BR-CORE-092) when {@code yhrm.audit.sealer.url} configures a connection of the role
 * {@code yhrm_audit_sealer}: sealing every {@code yhrm.audit.sealer.interval} (default 5 s) and the anchor of the
 * previous day at 00:05 Vietnam time. Until the SaaS job scheduler takes it over, one application instance should
 * enable it (concurrent sealers are safe: {@code seal_batch} locks the tenant's chain head).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "yhrm.audit.sealer.url")
class AuditSealerScheduling {

    private final AuditSealer sealer;
    private final JdbcTemplate jdbc;
    private final BusinessClock clock;

    AuditSealerScheduling(AuditSealer sealer, BusinessClock clock, @Value("${yhrm.audit.sealer.url}") String url,
            @Value("${yhrm.audit.sealer.username:yhrm_audit_sealer}") String username,
            @Value("${yhrm.audit.sealer.password:}") String password) {
        this.sealer = sealer;
        this.clock = clock;
        this.jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    @Scheduled(fixedDelayString = "${yhrm.audit.sealer.interval:5s}")
    void seal() {
        sealer.sealPending(jdbc);
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Ho_Chi_Minh")
    void anchor() {
        sealer.sealPending(jdbc);
        sealer.anchor(jdbc, clock.today().minusDays(1));
    }
}
