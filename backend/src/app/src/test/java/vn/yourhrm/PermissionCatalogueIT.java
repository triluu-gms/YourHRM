package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.core.internal.application.PermissionCatalogueRegistrar;
import vn.yourhrm.core.internal.application.PermissionCatalogueRegistrar.Outcome;
import vn.yourhrm.support.IntegrationTest;

/**
 * Permission catalogue registration (US-CORE-033). The runtime role has SELECT only on core.permission
 * (002_core.sql), so drift is detected and reported with the SQL for the release job instead of written.
 */
class PermissionCatalogueIT extends IntegrationTest {

    @Autowired
    PermissionCatalogueRegistrar registrar;

    @Test
    void devSeedMatchesTheCodeCatalogue() {
        assertThat(registrar.register()).isEqualTo(Outcome.UP_TO_DATE);
        assertThat(registrar.declared()).extracting(d -> d.def().code())
                .contains("core.role.view", "core.role_assignment.create", "core.user.update");
    }

    @Test
    void driftIsReportedBecauseTheRuntimeRoleCannotWriteTheGlobalCatalogue() {
        String original = ADMIN.queryForObject(
                "select name_en from core.permission where permission_code = 'core.location.view'", String.class);
        ADMIN.update("update core.permission set name_en = 'stale label' where permission_code = 'core.location.view'");
        try {
            assertThat(registrar.register()).isEqualTo(Outcome.PENDING_RELEASE_JOB);
            assertThat(registrar.lastPendingCodes()).containsExactly("core.location.view");
            // the rendered seed SQL (run by the release job) is idempotent and fixes the drift
            ADMIN.execute(PermissionCatalogueRegistrar.renderUpsertSql(registrar.declared()));
            assertThat(registrar.register()).isEqualTo(Outcome.UP_TO_DATE);
        } finally {
            ADMIN.update("update core.permission set name_en = ? where permission_code = 'core.location.view'", original);
        }
    }
}
