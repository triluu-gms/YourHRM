package vn.yourhrm.core.api;

import java.util.List;
import java.util.Objects;

/**
 * A document a module generates from tenant-editable templates (BR-CORE-102, US-CORE-104), e.g. a labour contract:
 * the module declares the merge fields it supplies; tenants upload DOCX or HTML templates using them as
 * {@code {{field}}} placeholders (images as {@code {{@field}}} in DOCX), and unknown fields are refused on upload.
 *
 * <p>Every template may also use Core's fields: {@code doc.number}, {@code doc.date} and the issuing legal entity's
 * {@code le.legal_name}, {@code le.short_name}, {@code le.tax_code}, {@code le.address}, {@code le.phone},
 * {@code le.email} and the images {@code le.letterhead} and {@code le.seal}.
 *
 * @param purpose          LABOUR_CONTRACT, DECISION, CERTIFICATE, PAYSLIP, LETTER or REPORT
 * @param documentTypeCode the registered document type generated files are stored as
 */
public record DocumentTemplateDefinition(String code, String ownerModule, String purpose, String nameVi,
        String nameEn, String documentTypeCode, List<MergeField> mergeFields) {

    /** {@code sample} fills previews; {@code image} fields take PNG / JPEG bytes. */
    public record MergeField(String key, String labelVi, String labelEn, String sample, boolean image) {

        public MergeField {
            Objects.requireNonNull(key, "key");
        }

        public static MergeField text(String key, String labelVi, String labelEn, String sample) {
            return new MergeField(key, labelVi, labelEn, sample, false);
        }
    }

    public DocumentTemplateDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(ownerModule, "ownerModule");
        Objects.requireNonNull(documentTypeCode, "documentTypeCode");
        mergeFields = mergeFields == null ? List.of() : List.copyOf(mergeFields);
    }
}
