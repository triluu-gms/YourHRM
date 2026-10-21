package vn.yourhrm.core.internal.application;

import static vn.yourhrm.shared.auth.api.PermissionAction.CREATE;
import static vn.yourhrm.shared.auth.api.PermissionAction.DELETE;
import static vn.yourhrm.shared.auth.api.PermissionAction.UPDATE;
import static vn.yourhrm.shared.auth.api.PermissionAction.VIEW;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.shared.auth.api.PermissionCatalogue;
import vn.yourhrm.shared.auth.api.PermissionDefinition;

/** Core's own permission catalogue (US-CORE-033). */
@Component
public class CorePermissionCatalogue implements PermissionCatalogue {

    @Override
    public String module() {
        return "core";
    }

    @Override
    public List<PermissionDefinition> permissions() {
        return List.of(
                PermissionDefinition.of(CorePermissions.PERMISSION_VIEW, VIEW, "Xem danh mục quyền", "View permission catalogue"),
                PermissionDefinition.of(CorePermissions.ROLE_VIEW, VIEW, "Xem vai trò", "View roles"),
                PermissionDefinition.of(CorePermissions.ROLE_CREATE, CREATE, "Tạo vai trò", "Create roles"),
                PermissionDefinition.of(CorePermissions.ROLE_UPDATE, UPDATE, "Sửa vai trò và ma trận quyền", "Update roles and permission matrix").asPrivileged(),
                PermissionDefinition.of(CorePermissions.ROLE_DELETE, DELETE, "Xóa vai trò", "Delete roles"),
                PermissionDefinition.of(CorePermissions.USER_VIEW, VIEW, "Xem người dùng", "View users"),
                PermissionDefinition.of(CorePermissions.USER_CREATE, CREATE, "Tạo người dùng", "Create users"),
                PermissionDefinition.of(CorePermissions.USER_UPDATE, UPDATE, "Sửa / kích hoạt / vô hiệu hóa người dùng", "Update / activate / deactivate users"),
                PermissionDefinition.of(CorePermissions.ROLE_ASSIGNMENT_VIEW, VIEW, "Xem gán vai trò", "View role assignments"),
                PermissionDefinition.of(CorePermissions.ROLE_ASSIGNMENT_CREATE, CREATE, "Gán vai trò", "Grant roles").asPrivileged(),
                PermissionDefinition.of(CorePermissions.ROLE_ASSIGNMENT_DELETE, DELETE, "Thu hồi vai trò", "Revoke roles").asPrivileged(),
                PermissionDefinition.of(CorePermissions.LEGAL_ENTITY_VIEW, VIEW, "Xem pháp nhân", "View legal entities"),
                PermissionDefinition.of(CorePermissions.LOCATION_VIEW, VIEW, "Xem địa điểm", "View locations"),
                PermissionDefinition.of(CorePermissions.ORG_UNIT_VIEW, VIEW, "Xem đơn vị tổ chức", "View org units"));
    }
}
