package vn.yourhrm.core.internal.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.DocumentOwnerResolver;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;

/**
 * Core's own document types and the owner resolver of its records: evidence of legal-entity master data (e.g. the OA /
 * OD rate decision), the legal entity's letterhead and seal images (BR-CORE-102) and uploaded import files.
 */
@Component
public class CoreDocumentTypes implements DocumentTypeCatalogue, DocumentOwnerResolver {

    public static final String EVIDENCE = "EVIDENCE";
    public static final String LE_LETTERHEAD = "LE_LETTERHEAD";
    public static final String LE_SEAL = "LE_SEAL";
    public static final String IMPORT_FILE = "IMPORT_FILE";
    public static final String LEGAL_ENTITY = "core.legal_entity";
    public static final String IMPORT_JOB = "core.import_job";
    static final Set<String> IMAGES = Set.of("image/png", "image/jpeg");

    @Override
    public String module() {
        return "core";
    }

    @Override
    public List<DocumentTypeDefinition> documentTypes() {
        return List.of(
                new DocumentTypeDefinition(EVIDENCE, "core", LEGAL_ENTITY, "Tài liệu chứng minh của pháp nhân",
                        "Legal entity evidence", "INTERNAL", "COMPANY_RECORDS", CorePermissions.LEGAL_ENTITY_VIEW,
                        CorePermissions.LEGAL_ENTITY_UPDATE, Set.of(), null),
                new DocumentTypeDefinition(LE_LETTERHEAD, "core", LEGAL_ENTITY, "Tiêu đề thư của pháp nhân",
                        "Legal entity letterhead", "INTERNAL", "COMPANY_RECORDS", CorePermissions.LEGAL_ENTITY_VIEW,
                        CorePermissions.LEGAL_ENTITY_UPDATE, IMAGES, 2L * 1024 * 1024),
                new DocumentTypeDefinition(LE_SEAL, "core", LEGAL_ENTITY, "Hình con dấu của pháp nhân",
                        "Legal entity seal image", "RESTRICTED", "COMPANY_RECORDS", CorePermissions.LEGAL_ENTITY_UPDATE,
                        CorePermissions.LEGAL_ENTITY_UPDATE, IMAGES, 2L * 1024 * 1024),
                new DocumentTypeDefinition(IMPORT_FILE, "core", IMPORT_JOB, "Tệp import", "Import file", "CONFIDENTIAL",
                        "IMPORT_STAGING", CorePermissions.IMPORT_VIEW, CorePermissions.IMPORT_VIEW,
                        Set.of(DocumentService.XLSX, "text/csv"), null));
    }

    @Override
    public Set<String> ownerEntityTypes() {
        return Set.of(LEGAL_ENTITY);
    }

    @Override
    public Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId) {
        return Optional.of(new AuthorizationTarget.LegalEntity(ownerEntityId));
    }
}
