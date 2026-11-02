package vn.yourhrm.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.DocumentOwnerResolver;
import vn.yourhrm.core.api.DocumentTemplateCatalogue;
import vn.yourhrm.core.api.DocumentTemplateDefinition;
import vn.yourhrm.core.api.DocumentTemplateDefinition.MergeField;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;

/**
 * Documents of a pretend People module (tests only): a confidential contract file type whose owner record (a
 * contract) resolves to the legal entity the test registers, and a labour-contract template definition.
 */
public class TestHrDocuments implements DocumentTypeCatalogue, DocumentOwnerResolver, DocumentTemplateCatalogue {

    public static final String CONTRACT = "TEST_CONTRACT";
    public static final String CONTRACT_TEMPLATE = "TEST_LABOUR_CONTRACT";
    private static final Map<UUID, UUID> CONTRACT_LE = new ConcurrentHashMap<>();

    /** A contract record of the pretend module, belonging to {@code legalEntityId}. */
    public static UUID contract(UUID legalEntityId) {
        UUID id = UUID.randomUUID();
        CONTRACT_LE.put(id, legalEntityId);
        return id;
    }

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<DocumentTypeDefinition> documentTypes() {
        return List.of(new DocumentTypeDefinition(CONTRACT, "hr", "hr.test_contract", "Hợp đồng lao động (test)",
                "Labour contract (test)", "CONFIDENTIAL", "EMPLOYEE_FILE", CorePermissions.LEGAL_ENTITY_VIEW,
                CorePermissions.LEGAL_ENTITY_UPDATE, Set.of(), null));
    }

    @Override
    public Set<String> ownerEntityTypes() {
        return Set.of("hr.test_contract");
    }

    @Override
    public Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId) {
        return Optional.ofNullable(CONTRACT_LE.get(ownerEntityId)).map(AuthorizationTarget.LegalEntity::new);
    }

    @Override
    public List<DocumentTemplateDefinition> templates() {
        return List.of(new DocumentTemplateDefinition(CONTRACT_TEMPLATE, "hr", "LABOUR_CONTRACT",
                "Hợp đồng lao động (test)", "Labour contract (test)", CONTRACT, List.of(
                        MergeField.text("employee.full_name", "Họ và tên", "Full name", "Nguyễn Thị Ánh"),
                        MergeField.text("contract.start_date", "Ngày bắt đầu", "Start date", "01/02/2026"),
                        MergeField.text("job.title", "Chức danh", "Job title", "Kỹ sư phần mềm"))));
    }
}
