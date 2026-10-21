package vn.yourhrm.shared.auth.api;

/** Actions allowed by the {@code core.permission.action} CHECK constraint (002_core.sql). */
public enum PermissionAction {
    VIEW, CREATE, UPDATE, DELETE, APPROVE, EXPORT, IMPORT, VIEW_SENSITIVE, EXECUTE, ADMIN
}
