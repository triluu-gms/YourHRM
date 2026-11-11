package vn.yourhrm.people.internal.application.privacy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentOwnerResolver;
import vn.yourhrm.core.api.DocumentTemplateCatalogue;
import vn.yourhrm.core.api.DocumentTemplateDefinition;
import vn.yourhrm.core.api.DocumentTemplateDefinition.MergeField;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;

/**
 * What the DSR handlers register with Core (US-PPL-088): the document type of the human-readable export (RESTRICTED,
 * viewable and managed with {@code hr.dsr.execute} only) with its owner resolver (an export belongs to the tenant: the
 * DPO's permission is tenant-wide), and the bilingual PDF template {@code HR_DSR_EXPORT} (purpose REPORT).
 */
@Component
class PrivacyCatalogues implements DocumentTypeCatalogue, DocumentTemplateCatalogue, DocumentOwnerResolver {

    static final List<MergeField> FIELDS = List.of(
            MergeField.text("person.full_name", "Họ và tên chủ thể dữ liệu", "Data subject", "Nguyễn Thị Ánh"),
            MergeField.text("request.ref", "Số yêu cầu", "Request reference", "DSR-2026-001"),
            MergeField.text("export.date", "Ngày xuất", "Export date", "05/11/2026"),
            MergeField.text("export.sections", "Các nhóm dữ liệu", "Data sections", "person, employment, contract"),
            MergeField.text("export.content", "Nội dung dữ liệu (không gồm dữ liệu P3)", "Data (P3 values omitted)",
                    "== person (1) ..."));

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<DocumentTypeDefinition> documentTypes() {
        return List.of(new DocumentTypeDefinition(PrivacyService.DOCUMENT_TYPE, "hr", PrivacyService.OWNER,
                "Gói dữ liệu cá nhân (yêu cầu của chủ thể dữ liệu)", "Personal data export (data subject request)",
                "RESTRICTED", "EMPLOYEE_FILE", PeoplePermissions.DSR_EXECUTE, PeoplePermissions.DSR_EXECUTE, Set.of(),
                null));
    }

    @Override
    public List<DocumentTemplateDefinition> templates() {
        return List.of(new DocumentTemplateDefinition(PrivacyService.TEMPLATE, "hr", "REPORT",
                "Dữ liệu cá nhân của chủ thể dữ liệu", "Personal data of the data subject", PrivacyService.DOCUMENT_TYPE,
                FIELDS));
    }

    @Override
    public Set<String> ownerEntityTypes() {
        return Set.of(PrivacyService.OWNER);
    }

    @Override
    public Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId) {
        return Optional.of(new AuthorizationTarget.Tenant());
    }
}
