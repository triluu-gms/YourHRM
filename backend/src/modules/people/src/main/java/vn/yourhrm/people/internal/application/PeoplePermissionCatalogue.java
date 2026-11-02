package vn.yourhrm.people.internal.application;

import static vn.yourhrm.shared.auth.api.PermissionAction.ADMIN;
import static vn.yourhrm.shared.auth.api.PermissionAction.CREATE;
import static vn.yourhrm.shared.auth.api.PermissionAction.EXECUTE;
import static vn.yourhrm.shared.auth.api.PermissionAction.EXPORT;
import static vn.yourhrm.shared.auth.api.PermissionAction.IMPORT;
import static vn.yourhrm.shared.auth.api.PermissionAction.UPDATE;
import static vn.yourhrm.shared.auth.api.PermissionAction.VIEW;
import static vn.yourhrm.shared.auth.api.PermissionAction.VIEW_SENSITIVE;
import static vn.yourhrm.shared.auth.api.PermissionDefinition.of;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.shared.auth.api.PermissionCatalogue;
import vn.yourhrm.shared.auth.api.PermissionDefinition;

/** People's permission catalogue (US-PPL-001), registered by Core into {@code core.permission}. */
@Component
class PeoplePermissionCatalogue implements PermissionCatalogue {

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<PermissionDefinition> permissions() {
        return List.of(
                of(PeoplePermissions.EMPLOYEE_VIEW, VIEW, "Xem hồ sơ nhân viên", "View employees"),
                of(PeoplePermissions.EMPLOYEE_EXPORT, EXPORT, "Xuất danh sách nhân viên", "Export employees"),
                of(PeoplePermissions.PERSON_CREATE, CREATE, "Tạo hồ sơ cá nhân", "Create persons"),
                of(PeoplePermissions.PERSON_UPDATE, UPDATE, "Sửa hồ sơ cá nhân", "Update persons"),
                of(PeoplePermissions.PERSON_VIEW_SENSITIVE, VIEW_SENSITIVE, "Xem dữ liệu cá nhân nhạy cảm",
                        "View sensitive personal data").asSensitive(),
                of(PeoplePermissions.PERSON_MERGE, ADMIN, "Gộp hồ sơ trùng", "Merge duplicate persons").asPrivileged(),
                of(PeoplePermissions.EMPLOYMENT_CREATE, CREATE, "Tuyển dụng", "Hire employees"),
                of(PeoplePermissions.EMPLOYMENT_UPDATE, UPDATE, "Sửa quan hệ lao động", "Update employments"),
                of(PeoplePermissions.ASSIGNMENT_UPDATE, UPDATE, "Thay đổi công việc", "Change assignments"),
                of(PeoplePermissions.COMPENSATION_VIEW, VIEW, "Xem lương thỏa thuận", "View compensation")
                        .asSensitive(),
                of(PeoplePermissions.COMPENSATION_UPDATE, UPDATE, "Đề xuất thay đổi lương", "Change compensation")
                        .asSensitive(),
                of(PeoplePermissions.CONTRACT_VIEW, VIEW, "Xem hợp đồng lao động", "View labour contracts"),
                of(PeoplePermissions.CONTRACT_CREATE, CREATE, "Tạo hợp đồng lao động", "Create labour contracts"),
                of(PeoplePermissions.CONTRACT_UPDATE, UPDATE, "Sửa hợp đồng lao động", "Update labour contracts"),
                of(PeoplePermissions.DECISION_VIEW, VIEW, "Xem quyết định nhân sự", "View HR decisions"),
                of(PeoplePermissions.DECISION_CREATE, CREATE, "Tạo quyết định nhân sự", "Create HR decisions"),
                of(PeoplePermissions.DECISION_UPDATE, UPDATE, "Sửa quyết định nhân sự", "Update HR decisions"),
                of(PeoplePermissions.DEPENDANT_VIEW, VIEW, "Xem người phụ thuộc", "View dependants"),
                of(PeoplePermissions.DEPENDANT_UPDATE, UPDATE, "Đăng ký người phụ thuộc", "Register dependants"),
                of(PeoplePermissions.BANK_ACCOUNT_VIEW, VIEW, "Xem tài khoản ngân hàng", "View bank accounts"),
                of(PeoplePermissions.BANK_ACCOUNT_UPDATE, UPDATE, "Sửa tài khoản ngân hàng", "Update bank accounts"),
                of(PeoplePermissions.TAX_RESIDENCY_UPDATE, UPDATE, "Cập nhật cư trú thuế", "Update tax residency"),
                of(PeoplePermissions.SPECIAL_STATUS_VIEW, VIEW, "Xem tình trạng đặc biệt", "View special statuses")
                        .asSensitive(),
                of(PeoplePermissions.SPECIAL_STATUS_UPDATE, UPDATE, "Cập nhật tình trạng đặc biệt",
                        "Update special statuses"),
                of(PeoplePermissions.WORK_PERMIT_UPDATE, UPDATE, "Cập nhật giấy phép lao động", "Update work permits"),
                of(PeoplePermissions.SEPARATION_VIEW, VIEW, "Xem hồ sơ nghỉ việc", "View separations"),
                of(PeoplePermissions.SEPARATION_CREATE, CREATE, "Tạo hồ sơ nghỉ việc", "Create separations"),
                of(PeoplePermissions.SEPARATION_UPDATE, UPDATE, "Sửa hồ sơ nghỉ việc", "Update separations"),
                of(PeoplePermissions.STATUTORY_REPORT_VIEW, VIEW, "Xem báo cáo lao động", "View labour reports"),
                of(PeoplePermissions.STATUTORY_REPORT_EXECUTE, EXECUTE, "Lập và nộp báo cáo lao động",
                        "Generate and file labour reports"),
                of(PeoplePermissions.IMPORT_RUN, IMPORT, "Import hồ sơ nhân viên", "Import employee data"),
                of(PeoplePermissions.DSR_EXECUTE, EXECUTE, "Xử lý yêu cầu chủ thể dữ liệu",
                        "Process data subject requests").asPrivileged(),
                of(PeoplePermissions.ESS_VIEW, VIEW, "Xem hồ sơ của tôi", "View my HR data"),
                of(PeoplePermissions.ESS_REQUEST, CREATE, "Gửi yêu cầu nhân sự", "Submit my HR requests"));
    }
}
