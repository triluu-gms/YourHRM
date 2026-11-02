package vn.yourhrm.core.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The document service (BR-CORE-100/101, interface P-21): every module stores files through it. Files live in the
 * SaaS object storage under the tenant's prefix; Core keeps the metadata, the versions and the access rules. A new
 * file or version is usable only after its antivirus scan came back clean.
 *
 * <p>Each call checks the current user against the document type's permissions on the owner record.
 */
public interface Documents {

    /**
     * @param ownerEntityId   the owner record; required (attach after creating the record)
     * @param legalEntityId   the owner's legal entity (scope fallback and filtering), optional
     * @param subjectPersonId the person the file is about (DSR fan-out), optional
     * @param confidentiality null = the type's default
     */
    record Upload(String documentTypeCode, UUID ownerEntityId, UUID legalEntityId, UUID subjectPersonId, String title,
            String confidentiality, String fileName, String mediaType, byte[] content) {

        public Upload {
            Objects.requireNonNull(documentTypeCode, "documentTypeCode");
            Objects.requireNonNull(ownerEntityId, "ownerEntityId");
            Objects.requireNonNull(content, "content");
        }
    }

    record DocumentVersionView(int versionNo, String fileName, String mediaType, long sizeBytes, String sha256,
            String source, String scanStatus, Instant createdAt, UUID uploadedBy) {
    }

    record DocumentView(UUID id, String documentTypeCode, String ownerModule, String ownerEntityType,
            UUID ownerEntityId, UUID legalEntityId, String title, String confidentiality, String retentionClass,
            String status, int currentVersionNo, List<DocumentVersionView> versions) {
    }

    record DownloadLink(URI url, Instant expiresAt, String fileName) {
    }

    DocumentView upload(Upload upload);

    /** A new version of the file; the previous versions stay available. */
    DocumentView addVersion(UUID documentId, String fileName, String mediaType, byte[] content);

    DocumentView get(UUID documentId);

    List<DocumentView> list(String ownerEntityType, UUID ownerEntityId);

    /** A short-lived signed URL of a clean version (null = current); confidential downloads are audited. */
    DownloadLink downloadLink(UUID documentId, Integer versionNo);
}
