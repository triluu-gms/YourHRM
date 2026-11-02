package vn.yourhrm.core.internal.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;

/** Core's own tenant settings (BR-CORE-084 examples; used by the user lifecycle of US-CORE-031/032). */
@Component
public class CoreSettings implements SettingCatalogue {

    public static final String AUTO_CREATE_USER = "core.auto_create_user";
    public static final String FORMER_EMPLOYEE_ACCESS_DAYS = "core.former_employee_access_days";
    /** Upload limits of the document service (BR-CORE-100, US-CORE-100: 20 MB by default). */
    public static final String DOCUMENT_MAX_UPLOAD_MB = "core.document.max_upload_mb";
    public static final String DOCUMENT_MEDIA_TYPES = "core.document.allowed_media_types";

    @Override
    public String module() {
        return "core";
    }

    @Override
    public List<SettingDefinition> settings() {
        return List.of(
                new SettingDefinition(AUTO_CREATE_USER, "BOOL", List.of(), false, Set.of("TENANT", "LEGAL_ENTITY"),
                        false, false, "Tự động tạo người dùng khi tuyển dụng",
                        "Create a user account automatically on hire"),
                new SettingDefinition(FORMER_EMPLOYEE_ACCESS_DAYS, "INT", List.of(), 90, Set.of("TENANT"), false, false,
                        "Số ngày nhân viên đã nghỉ còn truy cập tự phục vụ",
                        "Days a former employee keeps self-service access"),
                new SettingDefinition(DOCUMENT_MAX_UPLOAD_MB, "INT", List.of(), 20, Set.of("TENANT"), false, false,
                        "Dung lượng tối đa của một tệp tải lên (MB)", "Maximum size of an uploaded file (MB)"),
                new SettingDefinition(DOCUMENT_MEDIA_TYPES, "JSON", List.of(),
                        new ArrayList<>(DocumentService.DEFAULT_MEDIA_TYPES.stream().sorted().toList()), Set.of("TENANT"),
                        false, false, "Các loại tệp được phép tải lên", "Media types accepted for upload"));
    }
}
