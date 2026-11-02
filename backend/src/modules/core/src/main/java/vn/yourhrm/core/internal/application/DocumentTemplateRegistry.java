package vn.yourhrm.core.internal.application;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.DocumentTemplateCatalogue;
import vn.yourhrm.core.api.DocumentTemplateDefinition;
import vn.yourhrm.core.api.DocumentTemplateDefinition.MergeField;

/**
 * The generated-document definitions of all modules and their merge-field registry (BR-CORE-102, US-CORE-104),
 * validated at start-up: unique codes, a registered document type, unique keys outside Core's own fields.
 */
@Component
public class DocumentTemplateRegistry {

    static final Set<String> PURPOSES = Set.of("LABOUR_CONTRACT", "DECISION", "CERTIFICATE", "PAYSLIP", "LETTER",
            "REPORT");

    /** Core's fields, available in every template (the issuing legal entity and the document). */
    public static final List<MergeField> CORE_FIELDS = List.of(
            MergeField.text("doc.number", "Số văn bản", "Document number", "0001/2026/HĐLĐ-HQ"),
            MergeField.text("doc.date", "Ngày văn bản", "Document date", "01/01/2026"),
            MergeField.text("le.legal_name", "Tên pháp nhân", "Legal name", "Công ty TNHH Mẫu"),
            MergeField.text("le.short_name", "Tên viết tắt", "Short name", "MAU"),
            MergeField.text("le.tax_code", "Mã số thuế", "Tax code", "0101234567"),
            MergeField.text("le.address", "Địa chỉ", "Address", "1 Tràng Tiền, Phường Hoàn Kiếm, Hà Nội"),
            MergeField.text("le.phone", "Điện thoại", "Phone", "024 0000 0000"),
            MergeField.text("le.email", "E-mail", "E-mail", "hr@example.vn"),
            MergeField.text("le.legal_rep_name", "Người đại diện", "Legal representative", "Nguyễn Văn A"),
            MergeField.text("le.legal_rep_title", "Chức vụ người đại diện", "Representative's title", "Giám đốc"),
            new MergeField("le.letterhead", "Tiêu đề thư", "Letterhead", null, true),
            new MergeField("le.seal", "Con dấu", "Seal", null, true));

    private final Map<String, DocumentTemplateDefinition> definitions = new HashMap<>();

    public DocumentTemplateRegistry(List<DocumentTemplateCatalogue> catalogues, DocumentTypeRegistry types) {
        Set<String> coreKeys = new HashSet<>();
        CORE_FIELDS.forEach(f -> coreKeys.add(f.key()));
        for (DocumentTemplateCatalogue c : catalogues) {
            for (DocumentTemplateDefinition d : c.templates()) {
                if (!d.ownerModule().equals(c.module())) {
                    throw new IllegalStateException("Template " + d.code() + " is declared by " + c.module());
                }
                if (!PURPOSES.contains(d.purpose())) {
                    throw new IllegalStateException("Unknown purpose " + d.purpose() + " of template " + d.code());
                }
                if (types.find(d.documentTypeCode()).isEmpty()) {
                    throw new IllegalStateException("Template " + d.code() + " stores unknown document type "
                            + d.documentTypeCode());
                }
                Set<String> keys = new HashSet<>();
                for (MergeField f : d.mergeFields()) {
                    if (coreKeys.contains(f.key()) || f.key().startsWith("le.") || f.key().startsWith("doc.")
                            || !keys.add(f.key())) {
                        throw new IllegalStateException("Merge field " + f.key() + " of " + d.code()
                                + " is duplicate or reserved (le.*, doc.*)");
                    }
                }
                if (definitions.put(d.code(), d) != null) {
                    throw new IllegalStateException("Duplicate document template " + d.code());
                }
            }
        }
    }

    public Optional<DocumentTemplateDefinition> find(String code) {
        return Optional.ofNullable(definitions.get(code));
    }

    public List<DocumentTemplateDefinition> all() {
        return definitions.values().stream().sorted(Comparator.comparing(DocumentTemplateDefinition::code)).toList();
    }

    /** The module's fields plus Core's, by key. */
    public Map<String, MergeField> fields(DocumentTemplateDefinition d) {
        Map<String, MergeField> out = new HashMap<>();
        CORE_FIELDS.forEach(f -> out.put(f.key(), f));
        d.mergeFields().forEach(f -> out.put(f.key(), f));
        return out;
    }
}
