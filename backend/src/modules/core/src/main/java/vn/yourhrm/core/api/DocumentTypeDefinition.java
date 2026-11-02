package vn.yourhrm.core.api;

import java.util.Objects;
import java.util.Set;

/**
 * A kind of document a module stores through the document service (BR-CORE-100, US-CORE-101), registered at deploy.
 * Access to a document follows its owner record (BR-CORE-101): viewing needs {@code viewPermission} and adding or
 * replacing files needs {@code managePermission}, each with a data scope covering the owner record, which the owner
 * module resolves through a {@link DocumentOwnerResolver}.
 *
 * @param code                   e.g. {@code LABOUR_CONTRACT}, {@code EVIDENCE}
 * @param ownerEntityType        the record kind documents of this type belong to, e.g. {@code hr.contract}
 * @param defaultConfidentiality PUBLIC, INTERNAL, CONFIDENTIAL or RESTRICTED (downloads of the last two are audited)
 * @param retentionClass         key of the SaaS retention policy (BR-CORE-104)
 * @param mediaTypes             accepted media types; empty = the document service's default list
 * @param maxSizeBytes           a lower per-type limit than the tenant's upload limit, or null
 */
public record DocumentTypeDefinition(String code, String ownerModule, String ownerEntityType, String nameVi,
        String nameEn, String defaultConfidentiality, String retentionClass, String viewPermission,
        String managePermission, Set<String> mediaTypes, Long maxSizeBytes) {

    public static final Set<String> CONFIDENTIALITY = Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    public DocumentTypeDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(ownerModule, "ownerModule");
        Objects.requireNonNull(ownerEntityType, "ownerEntityType");
        Objects.requireNonNull(viewPermission, "viewPermission");
        Objects.requireNonNull(managePermission, "managePermission");
        Objects.requireNonNull(retentionClass, "retentionClass");
        if (!CONFIDENTIALITY.contains(defaultConfidentiality)) {
            throw new IllegalArgumentException("Unknown confidentiality " + defaultConfidentiality);
        }
        mediaTypes = mediaTypes == null ? Set.of() : Set.copyOf(mediaTypes);
    }
}
