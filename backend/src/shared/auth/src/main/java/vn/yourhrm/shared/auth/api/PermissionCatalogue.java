package vn.yourhrm.shared.auth.api;

import java.util.List;

/**
 * A module declares its permissions in code by exposing one bean of this type (US-CORE-033). Core
 * collects all catalogues at start-up and registers them in the global {@code core.permission} table
 * (idempotent; codes removed from code are deactivated, never deleted).
 */
public interface PermissionCatalogue {

    /** Owner module as allowed by {@code core.permission.owner_module}: saas, core, hr, att, pay, rec. */
    String module();

    List<PermissionDefinition> permissions();
}
