package vn.yourhrm.core.internal.application;

import static vn.yourhrm.shared.auth.api.PermissionAction.ADMIN;
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
                PermissionDefinition.of(CorePermissions.COMPANY_GROUP_VIEW, VIEW, "Xem hồ sơ tập đoàn", "View company group profile"),
                PermissionDefinition.of(CorePermissions.COMPANY_GROUP_UPDATE, UPDATE, "Sửa hồ sơ tập đoàn", "Update company group profile"),
                PermissionDefinition.of(CorePermissions.LEGAL_ENTITY_VIEW, VIEW, "Xem pháp nhân", "View legal entities"),
                PermissionDefinition.of(CorePermissions.LEGAL_ENTITY_CREATE, CREATE, "Tạo pháp nhân", "Create legal entities"),
                PermissionDefinition.of(CorePermissions.LEGAL_ENTITY_UPDATE, UPDATE, "Sửa pháp nhân", "Update legal entities"),
                PermissionDefinition.of(CorePermissions.LOCATION_VIEW, VIEW, "Xem địa điểm", "View locations"),
                PermissionDefinition.of(CorePermissions.LOCATION_CREATE, CREATE, "Tạo địa điểm", "Create locations"),
                PermissionDefinition.of(CorePermissions.LOCATION_UPDATE, UPDATE, "Sửa địa điểm", "Update locations"),
                PermissionDefinition.of(CorePermissions.ORG_UNIT_VIEW, VIEW, "Xem đơn vị tổ chức", "View org units"),
                PermissionDefinition.of(CorePermissions.ORG_UNIT_CREATE, CREATE, "Tạo đơn vị tổ chức", "Create org units"),
                PermissionDefinition.of(CorePermissions.ORG_UNIT_UPDATE, UPDATE, "Sửa đơn vị tổ chức", "Update org units"),
                PermissionDefinition.of(CorePermissions.ORG_UNIT_TYPE_UPDATE, UPDATE, "Quản lý loại đơn vị tổ chức", "Manage org unit types"),
                PermissionDefinition.of(CorePermissions.JOB_CATALOGUE_VIEW, VIEW, "Xem chức danh và ngạch bậc", "View job titles and grades"),
                PermissionDefinition.of(CorePermissions.JOB_CATALOGUE_UPDATE, UPDATE, "Quản lý chức danh và ngạch bậc", "Manage job titles and grades"),
                PermissionDefinition.of(CorePermissions.COST_CENTRE_VIEW, VIEW, "Xem trung tâm chi phí", "View cost centres"),
                PermissionDefinition.of(CorePermissions.COST_CENTRE_CREATE, CREATE, "Tạo trung tâm chi phí", "Create cost centres"),
                PermissionDefinition.of(CorePermissions.COST_CENTRE_UPDATE, UPDATE, "Sửa trung tâm chi phí", "Update cost centres"),
                PermissionDefinition.of(CorePermissions.EMPLOYEE_CATEGORY_VIEW, VIEW, "Xem loại nhân viên", "View employee categories"),
                PermissionDefinition.of(CorePermissions.EMPLOYEE_CATEGORY_UPDATE, UPDATE, "Quản lý loại nhân viên", "Manage employee categories"),
                PermissionDefinition.of(CorePermissions.WORKFLOW_VIEW, VIEW, "Xem yêu cầu phê duyệt trong phạm vi", "View approval requests in scope"),
                PermissionDefinition.of(CorePermissions.WORKFLOW_REASSIGN, UPDATE, "Chuyển giao việc phê duyệt", "Reassign approval tasks"),
                PermissionDefinition.of(CorePermissions.WORKFLOW_ADMIN, ADMIN, "Giám sát và can thiệp workflow", "Monitor and intervene in workflows"),
                PermissionDefinition.of(CorePermissions.WORKFLOW_DEFINITION_VIEW, VIEW, "Xem định nghĩa workflow", "View workflow definitions"),
                PermissionDefinition.of(CorePermissions.WORKFLOW_DEFINITION_MANAGE, UPDATE, "Quản lý định nghĩa workflow", "Manage workflow definitions"),
                PermissionDefinition.of(CorePermissions.NOTIFICATION_ADMIN, ADMIN, "Theo dõi và gửi lại thông báo", "Monitor and resend notifications"),
                PermissionDefinition.of(CorePermissions.NOTIFICATION_TEMPLATE_VIEW, VIEW, "Xem và xem trước mẫu thông báo", "View and preview notification templates"),
                PermissionDefinition.of(CorePermissions.SETTING_VIEW, VIEW, "Xem thiết lập", "View settings"),
                PermissionDefinition.of(CorePermissions.SETTING_UPDATE, UPDATE, "Sửa thiết lập", "Change settings"),
                PermissionDefinition.of(CorePermissions.SEQUENCE_VIEW, VIEW, "Xem chuỗi đánh số", "View number series"),
                PermissionDefinition.of(CorePermissions.SEQUENCE_UPDATE, UPDATE, "Quản lý chuỗi đánh số", "Manage number series"),
                PermissionDefinition.of(CorePermissions.SUPPORT_SESSION_VIEW, VIEW, "Xem phiên hỗ trợ của nền tảng", "View platform support sessions"),
                PermissionDefinition.of(CorePermissions.AUDIT_VIEW, VIEW, "Tra cứu nhật ký kiểm toán", "Search the audit log"),
                PermissionDefinition.of(CorePermissions.AUDIT_VIEW_SENSITIVE, VIEW, "Xem giá trị nhạy cảm trong nhật ký kiểm toán", "View sensitive values in the audit log").asSensitive(),
                PermissionDefinition.of(CorePermissions.DOCUMENT_TEMPLATE_VIEW, VIEW, "Xem và xem trước mẫu văn bản", "View and preview document templates"),
                PermissionDefinition.of(CorePermissions.DOCUMENT_TEMPLATE_MANAGE, UPDATE, "Quản lý mẫu văn bản, tiêu đề thư và con dấu", "Manage document templates, letterhead and seal"),
                PermissionDefinition.of(CorePermissions.IMPORT_VIEW, VIEW, "Xem lịch sử import", "View import history"),
                PermissionDefinition.of(CorePermissions.IMPORT_RUN, CREATE, "Import dữ liệu cơ cấu tổ chức", "Import organisation data"));
    }
}
