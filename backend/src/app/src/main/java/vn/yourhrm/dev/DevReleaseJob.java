package vn.yourhrm.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.internal.application.PermissionCatalogueRegistrar;
import vn.yourhrm.core.internal.application.ProcessTypeRegistrar;
import vn.yourhrm.core.internal.application.SettingRegistrar;

/**
 * LOCAL DEVELOPMENT ONLY ({@code dev} profile): stand-in for the release job (US-TECH-024), as {@code TestReleaseJob}
 * does in the integration tests. Before Core's registrars verify the global catalogues at start-up, it upserts every
 * code-declared permission, process type and setting definition, connected as {@code yhrm_platform} (the role the real
 * release job uses; the runtime roles keep SELECT only). A freshly reset dev database therefore needs no manual
 * catalogue step when a module adds permissions.
 */
@Component
@Profile("dev")
public class DevReleaseJob {

    private static final Logger log = LoggerFactory.getLogger(DevReleaseJob.class);

    private final PermissionCatalogueRegistrar permissions;
    private final ProcessTypeRegistrar processTypes;
    private final SettingRegistrar settings;
    private final String url;
    private final String username;
    private final String password;

    public DevReleaseJob(PermissionCatalogueRegistrar permissions, ProcessTypeRegistrar processTypes,
            SettingRegistrar settings, @Value("${spring.datasource.url}") String url,
            @Value("${yhrm.dev.release-job.username:yhrm_platform}") String username,
            @Value("${yhrm.dev.release-job.password:yhrm_dev}") String password) {
        this.permissions = permissions;
        this.processTypes = processTypes;
        this.settings = settings;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            JdbcTemplate platform = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
            platform.execute(PermissionCatalogueRegistrar.renderUpsertSql(permissions.declared()));
            processTypes.declared().values().forEach(d -> platform.execute(processTypes.upsertSqlLiteral(d)));
            NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(platform);
            settings.declared().forEach(d -> named.update(SettingRegistrar.UPSERT, settings.params(d)));
            log.info("DEV release job: {} permissions, {} process types, {} settings upserted as {}",
                    permissions.declared().size(), processTypes.declared().size(), settings.declared().size(), username);
        } catch (RuntimeException e) {
            log.warn("DEV release job failed ({}); catalogues are only verified", e.getMessage());
        }
    }
}
