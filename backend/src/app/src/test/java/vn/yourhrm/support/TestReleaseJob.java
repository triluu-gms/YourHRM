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
 * the code-declared rows of the modules that the dev seed does not mirror (People, owner module {@code hr}; Time & Attendance, {@code att}) as the
 * superuser, exactly as {@code yhrm_platform} would in a real environment (T&amp;A's default attendance codes are written
 * as {@code yhrm_platform} itself, which proves its grants). The runtime role keeps SELECT only.
 */
public class TestReleaseJob {

    private static final List<String> MODULES = List.of("hr", "att");

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
        // T&A platform default attendance codes (sentinel tenant rows, BR-ATT-033), written as yhrm_platform like
        // the real release job (004_att v0.9 policy platform_global: sentinel tenant only)
        PostgresTestDatabase.platform().execute(
                vn.yourhrm.attendance.internal.application.timesheet.TimesheetCatalogues.defaultCodesUpsertSql());
    }
}
