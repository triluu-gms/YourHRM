package vn.yourhrm.core.api;

/** Permission codes owned by Core (registered through Core's {@code PermissionCatalogue}). */
public final class CorePermissions {

    public static final String PERMISSION_VIEW = "core.permission.view";

    public static final String ROLE_VIEW = "core.role.view";
    public static final String ROLE_CREATE = "core.role.create";
    public static final String ROLE_UPDATE = "core.role.update";
    public static final String ROLE_DELETE = "core.role.delete";

    public static final String USER_VIEW = "core.user.view";
    public static final String USER_CREATE = "core.user.create";
    public static final String USER_UPDATE = "core.user.update";

    public static final String ROLE_ASSIGNMENT_VIEW = "core.role_assignment.view";
    public static final String ROLE_ASSIGNMENT_CREATE = "core.role_assignment.create";
    public static final String ROLE_ASSIGNMENT_DELETE = "core.role_assignment.delete";

    public static final String LEGAL_ENTITY_VIEW = "core.legal_entity.view";
    public static final String LOCATION_VIEW = "core.location.view";
    public static final String ORG_UNIT_VIEW = "core.org_unit.view";

    private CorePermissions() {
    }
}
