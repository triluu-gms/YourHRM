package vn.yourhrm.support;

import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.yourhrm.core.internal.application.PermissionCatalogueRegistrar;
import vn.yourhrm.core.internal.application.ProcessTypeRegistrar;
import vn.yourhrm.core.internal.application.SettingRegistrar;

/**
 * Stand-in for the release job (tests only): before Core's registrars verify the global catalogues at start-up, writes
 * the code-declared rows of the modules that the dev seed does not mirror (People, owner module {@code hr}) as the
 * superuser, exactly as {@code yhrm_platform} would in a real environment. The runtime role keeps SELECT only.
 */
public class TestReleaseJob {

    private static final List<String> MODULES = List.of("hr");

    private final PermissionCatalogueRegistrar permissions;
    private final ProcessTypeRegistrar processTypes;
    private final SettingRegistrar settings;

    public TestReleaseJob(PermissionCatalogueRegistrar permissions, ProcessTypeRegistrar processTypes,
            SettingRegistrar settings) {
        this.permissions = permissions;
        this.processTypes = processTypes;
        this.settings = settings;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        JdbcTemplate admin = PostgresTestDatabase.admin();
        var perms = permissions.declared().stream().filter(d -> MODULES.contains(d.module())).toList();
        if (!perms.isEmpty()) {
            admin.execute(PermissionCatalogueRegistrar.renderUpsertSql(perms));
        }
        processTypes.declared().values().stream().filter(d -> MODULES.contains(d.module()))
                .forEach(d -> admin.execute(processTypes.upsertSqlLiteral(d)));
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(admin);
        settings.declared().stream().filter(d -> MODULES.contains(d.module()))
                .forEach(d -> named.update(SettingRegistrar.UPSERT, settings.params(d)));
    }
}
