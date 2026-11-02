package vn.yourhrm.core.api;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Generates a document from the tenant's template (BR-CORE-102, US-CORE-103/104): picks the template published for
 * the legal entity (else the tenant-wide one) on {@code onDate}, draws the next number of the template's number series
 * (BR-CORE-085) in the caller's transaction, merges the module's fields with the legal entity's letterhead, seal and
 * details, renders PDF/A (or DOCX) and stores the file with the template version it came from.
 *
 * <p>Called by the owner module, which has already authorised its own operation (e.g. issuing a contract).
 */
public interface DocumentGeneration {

    /** @param data values of the definition's merge fields used by the template (Strings, numbers; images as byte[]) */
    record GenerateRequest(String templateCode, UUID legalEntityId, UUID ownerEntityId, UUID subjectPersonId,
            String title, LocalDate onDate, Map<String, Object> data) {

        public GenerateRequest {
            Objects.requireNonNull(templateCode, "templateCode");
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(ownerEntityId, "ownerEntityId");
            data = data == null ? Map.of() : Map.copyOf(data);
        }
    }

    /** {@code documentNumber} is null when the template has no number series. */
    record Generated(UUID documentId, String documentNumber, UUID templateVersionId, String mediaType) {
    }

    Generated generate(GenerateRequest request);
}
