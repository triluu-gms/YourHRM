package vn.yourhrm.core.internal.application;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.DocumentGeneration;
import vn.yourhrm.core.api.DocumentTemplateDefinition;
import vn.yourhrm.core.api.DocumentTemplateDefinition.MergeField;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.core.internal.infra.AuditWriter;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.core.internal.infra.TemplateRenderer;
import vn.yourhrm.core.internal.infra.TemplateRenderer.Tag;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Tenant document templates and generation (BR-CORE-102, US-CORE-103/104). A template belongs to a module's
 * definition (its merge fields); tenants upload versions (DOCX or HTML) whose placeholders are checked against the
 * registry, preview them with sample data and publish them from a date (one published version per date). Generation
 * picks the legal entity's template, else the tenant-wide one, else the platform's global template (copied into the
 * tenant on first use), numbers the document (BR-CORE-085) and stores the file with the template version used.
 */
@Service
public class DocumentTemplateService implements DocumentGeneration {

    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record TemplateVersionView(UUID id, int versionNo, String sourceFormat, String outputFormat, String status,
            LocalDate validFrom, LocalDate validTo, List<String> mergeFields) {
    }

    public record TemplateView(UUID id, String code, String ownerModule, String purpose, String nameVi, String nameEn,
            UUID legalEntityId, String numberingSequenceCode, String status, List<TemplateVersionView> versions) {
    }

    public record Rendered(byte[] content, String mediaType, String fileExtension) {
    }

    private record TemplateRow(UUID tenantId, UUID id, String code, UUID legalEntityId, String sequenceCode) {
    }

    private record VersionRow(UUID id, UUID templateId, String sourceFormat, String outputFormat, String objectKey,
            List<String> mergeFields, String status, LocalDate validFrom) {
    }

    private final JdbcClient jdbc;
    private final DocumentTemplateRegistry registry;
    private final DocumentTypeRegistry types;
    private final DocumentService documents;
    private final SequenceService numbering;
    private final TemplateRenderer renderer;
    private final AuthorizationService authorization;
    private final AuditWriter audit;
    private final CoreJson json;
    private final BusinessClock clock;

    public DocumentTemplateService(JdbcClient jdbc, DocumentTemplateRegistry registry, DocumentTypeRegistry types,
            DocumentService documents, SequenceService numbering, TemplateRenderer renderer,
            AuthorizationService authorization, AuditWriter audit, CoreJson json, BusinessClock clock) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.types = types;
        this.documents = documents;
        this.numbering = numbering;
        this.renderer = renderer;
        this.authorization = authorization;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ----------------------------------------------------------------------------------------- administration

    public List<DocumentTemplateDefinition> definitions() {
        return registry.all();
    }

    @Transactional(readOnly = true)
    public List<TemplateView> list() {
        return jdbc.sql("""
                select id from core.document_template where tenant_id = :t order by code, legal_entity_id nulls first""")
                .param("t", tenant()).query(UUID.class).list().stream().map(this::view).toList();
    }

    @Transactional
    public TemplateView create(String code, UUID legalEntityId, String sequenceCode) {
        DocumentTemplateDefinition d = definition(code);
        if (sequenceCode != null && jdbc.sql("select count(*) from core.sequence_definition where tenant_id = :t and code = :c")
                .param("t", tenant()).param("c", sequenceCode).query(Integer.class).single() == 0) {
            throw ApiException.unprocessable("unknown-sequence", "Number series " + sequenceCode + " does not exist");
        }
        if (legalEntityId != null && jdbc.sql("select count(*) from core.legal_entity where tenant_id = :t and id = :le")
                .param("t", tenant()).param("le", legalEntityId).query(Integer.class).single() == 0) {
            throw ApiException.notFound("Legal entity", legalEntityId);
        }
        UUID id = Ids.newId();
        int n = jdbc.sql("""
                insert into core.document_template (tenant_id, id, code, owner_module, purpose, name_vi, name_en,
                       legal_entity_id, numbering_sequence_code, created_by)
                values (:t, :id, :code, :module, :purpose, :vi, :en, :le, :seq, :by)
                on conflict (tenant_id, code, legal_entity_id) do nothing""")
                .param("t", tenant()).param("id", id).param("code", code).param("module", d.ownerModule())
                .param("purpose", d.purpose()).param("vi", d.nameVi()).param("en", d.nameEn())
                .param("le", legalEntityId).param("seq", sequenceCode).param("by", user())
                .update();
        if (n == 0) {
            throw ApiException.conflict("template-exists", "A template " + code + " already exists for this scope");
        }
        return view(id);
    }

    /** A new DRAFT version; its placeholders must all be registered fields (image tags for image fields only). */
    @Transactional
    public TemplateView uploadVersion(UUID templateId, String fileName, byte[] content, LocalDate validFrom,
            String outputFormat) {
        TemplateRow t = template(templateId);
        DocumentTemplateDefinition d = definition(t.code());
        String name = DocumentService.fileName(fileName).toLowerCase(Locale.ROOT);
        String source = name.endsWith(".docx") ? "DOCX" : name.endsWith(".html") || name.endsWith(".htm") ? "HTML" : null;
        if (source == null || ("DOCX".equals(source) && !DocumentService.contentMatches(DocumentService.DOCX, content))
                || ("HTML".equals(source) && !DocumentService.contentMatches("text/html", content))) {
            throw ApiException.unprocessable("invalid-template-file", "A template is a .docx or .html file");
        }
        String output = outputFormat == null ? "PDF" : outputFormat;
        if (!Set.of("PDF", "DOCX").contains(output) || ("HTML".equals(source) && "DOCX".equals(output))) {
            throw ApiException.unprocessable("invalid-output-format", "HTML renders to PDF; DOCX to DOCX or PDF");
        }
        Set<Tag> tags;
        try {
            tags = renderer.tags(source, content);
        } catch (RuntimeException ex) {
            throw ApiException.unprocessable("invalid-template-file", "The template cannot be read: " + ex.getMessage());
        }
        Map<String, MergeField> fields = registry.fields(d);
        List<String> unknown = new ArrayList<>();
        Set<String> used = new TreeSet<>();
        for (Tag tag : tags) {
            MergeField f = fields.get(tag.key());
            if (f == null || ("DOCX".equals(source) && tag.image() != f.image())) {
                unknown.add(tag.image() ? "@" + tag.key() : tag.key());
            } else {
                used.add(tag.key());
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.unprocessable("unknown-merge-fields",
                    "The template uses fields that are not registered for " + t.code() + " (or the wrong tag kind)",
                    Map.of("fields", unknown));
        }
        int next = jdbc.sql("select coalesce(max(version_no), 0) + 1 from core.document_template_version where tenant_id = :t and document_template_id = :id")
                .param("t", tenant()).param("id", templateId).query(Integer.class).single();
        String key = tenant() + "/core/templates/" + Ids.newId();
        jdbc.sql("""
                insert into core.document_template_version (tenant_id, id, document_template_id, version_no,
                       source_format, output_format, object_key, sha256, merge_fields, status, valid_from, created_by)
                values (:t, :id, :tpl, :no, :src, :out, :key, :sha, cast(:fields as jsonb), 'DRAFT', :from, :by)""")
                .param("t", tenant()).param("id", Ids.newId()).param("tpl", templateId).param("no", next)
                .param("src", source).param("out", output).param("key", key)
                .param("sha", DocumentService.sha256(content)).param("fields", json.write(List.copyOf(used)))
                .param("from", Date.valueOf(validFrom == null ? clock.today() : validFrom)).param("by", user())
                .update();
        // a template source is parsed and validated here and never served to users; it is stored without a scan
        documents.storage().put(tenant(), key, "DOCX".equals(source) ? DocumentService.DOCX : "text/html", content,
                false);
        return view(templateId);
    }

    /** Renders a version with the definition's sample values (and the legal entity's real details when set). */
    @Transactional(readOnly = true)
    public Rendered preview(UUID templateId, UUID versionId) {
        TemplateRow t = template(templateId);
        DocumentTemplateDefinition d = definition(t.code());
        VersionRow v = version(t, versionId);
        Map<String, Object> values = new HashMap<>();
        for (MergeField f : registry.fields(d).values()) {
            if (!f.image() && f.sample() != null) {
                values.put(f.key(), f.sample());
            }
        }
        LocalDate today = clock.today();
        if (t.legalEntityId() != null) {
            values.putAll(legalEntityFields(t.legalEntityId(), today));
        }
        values.put("doc.date", VN_DATE.format(today));
        // a series counted per legal entity has no number for a tenant-wide template: the sample stays
        if (t.sequenceCode() != null && (t.legalEntityId() != null || jdbc.sql("""
                select scope_level = 'TENANT' from core.sequence_definition where tenant_id = :t and code = :c""")
                .param("t", tenant()).param("c", t.sequenceCode()).query(Boolean.class).optional().orElse(false))) {
            values.put("doc.number", numbering.preview(t.sequenceCode(), t.legalEntityId(), today));
        }
        return render(t, v, values);
    }

    /** Publishes a DRAFT from its start date; the version published before it ends the day before. */
    @Transactional
    public TemplateView publish(UUID templateId, UUID versionId) {
        TemplateRow t = template(templateId);
        VersionRow v = version(t, versionId);
        if (!"DRAFT".equals(v.status())) {
            throw ApiException.conflict("template-version-not-draft", "Only a draft can be published");
        }
        lockTemplate(templateId);
        int later = jdbc.sql("""
                select count(*) from core.document_template_version
                 where tenant_id = :t and document_template_id = :id and status = 'PUBLISHED' and valid_from >= :from""")
                .param("t", tenant()).param("id", templateId).param("from", Date.valueOf(v.validFrom()))
                .query(Integer.class).single();
        if (later > 0) {
            throw ApiException.conflict("template-version-superseded",
                    "A version published from the same or a later date exists; publish from a later date");
        }
        jdbc.sql("""
                update core.document_template_version set valid_to = :from
                 where tenant_id = :t and document_template_id = :id and status = 'PUBLISHED'
                   and (valid_to is null or valid_to > :from)""")
                .param("t", tenant()).param("id", templateId).param("from", Date.valueOf(v.validFrom())).update();
        jdbc.sql("""
                update core.document_template_version set status = 'PUBLISHED', published_at = now(), published_by = :by
                 where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", versionId).param("by", user()).update();
        return view(templateId);
    }

    /**
     * The legal entity's letterhead and seal images (BR-CORE-102): clean documents of the types LE_LETTERHEAD / LE_SEAL
     * attached to that legal entity. Null clears an image.
     */
    @Transactional
    public void setLetterhead(UUID legalEntityId, UUID letterheadDocumentId, UUID sealDocumentId) {
        if (!authorization.can(CorePermissions.LEGAL_ENTITY_UPDATE, new AuthorizationTarget.LegalEntity(legalEntityId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Updating this legal entity is not granted");
        }
        checkImage(letterheadDocumentId, CoreDocumentTypes.LE_LETTERHEAD, legalEntityId);
        checkImage(sealDocumentId, CoreDocumentTypes.LE_SEAL, legalEntityId);
        int n = jdbc.sql("""
                update core.legal_entity set letterhead_doc_id = :lh, seal_doc_id = :seal, updated_by = :by
                 where tenant_id = :t and id = :le""")
                .param("lh", letterheadDocumentId).param("seal", sealDocumentId).param("by", user())
                .param("t", tenant()).param("le", legalEntityId).update();
        if (n == 0) {
            throw ApiException.notFound("Legal entity", legalEntityId);
        }
    }

    private void checkImage(UUID documentId, String type, UUID legalEntityId) {
        if (documentId == null) {
            return;
        }
        var d = documents.find(documentId).orElseThrow(() -> ApiException.notFound("Document", documentId));
        if (!type.equals(d.documentTypeCode()) || !legalEntityId.equals(d.ownerEntityId())
                || !"ACTIVE".equals(d.status())) {
            throw ApiException.unprocessable("invalid-letterhead-document",
                    "Use an active (scanned) " + type + " document of this legal entity");
        }
    }

    // ---------------------------------------------------------------------------------------------- generation

    @Override
    @Transactional
    public Generated generate(GenerateRequest r) {
        DocumentTemplateDefinition d = definition(r.templateCode());
        LocalDate onDate = r.onDate() == null ? clock.today() : r.onDate();
        TemplateRow t = resolveTemplate(r.templateCode(), r.legalEntityId());
        VersionRow v = jdbc.sql(VERSION_SQL + " " + """
                 where tenant_id = :t and document_template_id = :id and status = 'PUBLISHED'
                   and valid_during @> cast(:on as date)""")
                .param("t", tenant()).param("id", t.id()).param("on", Date.valueOf(onDate))
                .query(this::mapVersion).optional()
                .orElseThrow(() -> ApiException.unprocessable("no-published-template",
                        "No version of template " + r.templateCode() + " is published for " + onDate));
        Map<String, MergeField> fields = registry.fields(d);
        Map<String, Object> values = new HashMap<>(legalEntityFields(r.legalEntityId(), onDate));
        values.put("doc.date", VN_DATE.format(onDate));
        String number = t.sequenceCode() == null ? null : numbering.next(t.sequenceCode(), r.legalEntityId(), onDate);
        if (number != null) {
            values.put("doc.number", number);
        }
        List<String> missing = new ArrayList<>();
        for (String key : v.mergeFields()) {
            if (key.startsWith("le.") || key.startsWith("doc.")) {
                continue;
            }
            Object value = r.data().get(key);
            if (value == null || (fields.get(key).image() != (value instanceof byte[]))) {
                missing.add(key);
            } else {
                values.put(key, value);
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("missing-merge-fields", "Values are missing for the template's fields",
                    Map.of("fields", missing));
        }
        if (v.mergeFields().contains("doc.number") && number == null) {
            throw ApiException.unprocessable("template-not-numbered",
                    "The template prints a document number but has no number series");
        }
        Rendered out = render(t, v, values);
        DocumentTypeDefinition type = types.find(d.documentTypeCode()).orElseThrow();
        String title = r.title() != null ? r.title() : d.nameVi() + (number == null ? "" : " " + number);
        String fileName = r.templateCode() + "-" + (number == null ? onDate.toString() : number.replaceAll("[^A-Za-z0-9-]", "-"))
                + "." + out.fileExtension();
        UUID documentId = documents.storeGenerated(type, r.ownerEntityId(), r.legalEntityId(), r.subjectPersonId(),
                title, fileName, out.mediaType(), out.content(), v.id());
        audit.append(AuditWriter.Event.ofCurrentActor("GENERATE", d.ownerModule(), "document", documentId,
                r.legalEntityId(), Map.of("template", r.templateCode(), "templateVersionId", v.id().toString(),
                        "documentNumber", number == null ? "" : number), false));
        return new Generated(documentId, number, v.id(), out.mediaType());
    }

    private Rendered render(TemplateRow t, VersionRow v, Map<String, Object> values) {
        UUID storeTenant = UUID.fromString(v.objectKey().substring(0, 36));
        byte[] source = documents.storage().get(storeTenant, v.objectKey());
        if ("HTML".equals(v.sourceFormat())) {
            return new Rendered(renderer.htmlToPdf(source, values), DocumentService.PDF, "pdf");
        }
        byte[] docx = renderer.mergeDocx(source, values);
        if ("DOCX".equals(v.outputFormat())) {
            return new Rendered(docx, DocumentService.DOCX, "docx");
        }
        // DOCX -> PDF/A runs in the Gotenberg (LibreOffice) service of ADR-0015, not deployed yet
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "pdf-converter-unavailable",
                "Converting DOCX templates to PDF needs the document converter service; publish a DOCX-output version");
    }

    /** The legal entity's template, else the tenant-wide one, else the global template (copied on first use). */
    private TemplateRow resolveTemplate(String code, UUID legalEntityId) {
        List<TemplateRow> rows = jdbc.sql("""
                select tenant_id, id, code, legal_entity_id, numbering_sequence_code from core.document_template
                 where code = :code and status = 'ACTIVE' and (legal_entity_id = :le or legal_entity_id is null)
                 order by (tenant_id = sys.global_tenant_id()), legal_entity_id nulls last""")
                .param("code", code).param("le", legalEntityId)
                .query((rs, n) -> new TemplateRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5)))
                .list();
        if (rows.isEmpty()) {
            throw ApiException.unprocessable("no-template", "The tenant has no template " + code);
        }
        TemplateRow first = rows.getFirst();
        return first.tenantId().equals(tenant()) ? first : copyGlobal(first);
    }

    private TemplateRow copyGlobal(TemplateRow global) {
        DocumentTemplateDefinition d = definition(global.code());
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into core.document_template (tenant_id, id, code, owner_module, purpose, name_vi, name_en,
                       numbering_sequence_code)
                values (:t, :id, :code, :module, :purpose, :vi, :en, :seq)
                on conflict (tenant_id, code, legal_entity_id) do nothing""")
                .param("t", tenant()).param("id", id).param("code", global.code()).param("module", d.ownerModule())
                .param("purpose", d.purpose()).param("vi", d.nameVi()).param("en", d.nameEn())
                .param("seq", global.sequenceCode()).update();
        UUID tenantTemplate = jdbc.sql("""
                select id from core.document_template where tenant_id = :t and code = :code and legal_entity_id is null""")
                .param("t", tenant()).param("code", global.code()).query(UUID.class).single();
        if (tenantTemplate.equals(id)) {
            jdbc.sql("""
                    insert into core.document_template_version (tenant_id, id, document_template_id, version_no,
                           source_format, output_format, object_key, sha256, merge_fields, status, valid_from, valid_to,
                           published_at)
                    select :t, sys.uuid_v7(), :id, version_no, source_format, output_format, object_key, sha256,
                           merge_fields, status, valid_from, valid_to, published_at
                      from core.document_template_version
                     where tenant_id = :g and document_template_id = :gid and status = 'PUBLISHED'""")
                    .param("t", tenant()).param("id", id).param("g", global.tenantId()).param("gid", global.id())
                    .update();
        }
        return new TemplateRow(tenant(), tenantTemplate, global.code(), null, global.sequenceCode());
    }

    /** {@code le.*} fields of the legal entity as of the date, with its letterhead and seal images. */
    private Map<String, Object> legalEntityFields(UUID legalEntityId, LocalDate onDate) {
        record Le(Map<String, Object> fields, UUID letterhead, UUID seal) {
        }
        Le le = jdbc.sql("""
                select v.legal_name_vi, v.short_name, v.tax_code, v.phone, v.email, v.legal_rep_name, v.legal_rep_title,
                       concat_ws(', ', nullif(v.address_line, ''), w.name_vi, p.name_vi) as address,
                       e.letterhead_doc_id, e.seal_doc_id
                  from core.legal_entity e
                  join core.legal_entity_version v on v.tenant_id = e.tenant_id and v.legal_entity_id = e.id
                       and v.valid_during @> cast(:on as date)
                  left join core.admin_unit w on w.admin_unit_code = v.admin_unit_code
                  left join core.admin_unit p on p.admin_unit_code = w.parent_code
                 where e.tenant_id = :t and e.id = :le""")
                .param("t", tenant()).param("le", legalEntityId).param("on", Date.valueOf(onDate))
                .query((rs, n) -> {
                    Map<String, Object> f = new HashMap<>();
                    f.put("le.legal_name", nz(rs.getString(1)));
                    f.put("le.short_name", nz(rs.getString(2)));
                    f.put("le.tax_code", nz(rs.getString(3)));
                    f.put("le.phone", nz(rs.getString(4)));
                    f.put("le.email", nz(rs.getString(5)));
                    f.put("le.legal_rep_name", nz(rs.getString(6)));
                    f.put("le.legal_rep_title", nz(rs.getString(7)));
                    f.put("le.address", nz(rs.getString(8)));
                    return new Le(f, rs.getObject(9, UUID.class), rs.getObject(10, UUID.class));
                })
                .optional()
                .orElseThrow(() -> ApiException.unprocessable("legal-entity-not-valid",
                        "The legal entity " + legalEntityId + " has no master data on " + onDate));
        Map<String, Object> out = new HashMap<>(le.fields());
        image(le.letterhead()).ifPresent(b -> out.put("le.letterhead", b));
        image(le.seal()).ifPresent(b -> out.put("le.seal", b));
        return out;
    }

    private Optional<byte[]> image(UUID documentId) {
        if (documentId == null) {
            return Optional.empty();
        }
        return documents.find(documentId).filter(d -> d.currentVersionNo() > 0)
                .map(d -> documents.content(documentId, d.currentVersionNo()));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static final String VERSION_SQL = """
            select id, document_template_id, source_format, output_format, object_key, merge_fields::text, status,
                   valid_from
              from core.document_template_version""";

    private VersionRow mapVersion(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new VersionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), json.listOfStrings(rs.getString(6)), rs.getString(7),
                rs.getObject(8, LocalDate.class));
    }

    private VersionRow version(TemplateRow t, UUID versionId) {
        return jdbc.sql(VERSION_SQL + " where tenant_id = :t and id = :id and document_template_id = :tpl")
                .param("t", tenant()).param("id", versionId).param("tpl", t.id())
                .query(this::mapVersion).optional()
                .orElseThrow(() -> ApiException.notFound("Template version", versionId));
    }

    private TemplateRow template(UUID id) {
        return jdbc.sql("""
                select tenant_id, id, code, legal_entity_id, numbering_sequence_code from core.document_template
                 where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id)
                .query((rs, n) -> new TemplateRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5)))
                .optional().orElseThrow(() -> ApiException.notFound("Document template", id));
    }

    private void lockTemplate(UUID id) {
        jdbc.sql("select id from core.document_template where tenant_id = :t and id = :id for update")
                .param("t", tenant()).param("id", id).query(UUID.class).single();
    }

    private TemplateView view(UUID id) {
        List<TemplateVersionView> versions = jdbc.sql("""
                select id, version_no, source_format, output_format, status, valid_from, valid_to, merge_fields::text
                  from core.document_template_version where tenant_id = :t and document_template_id = :id
                 order by version_no""")
                .param("t", tenant()).param("id", id)
                .query((rs, n) -> new TemplateVersionView(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class), json.listOfStrings(rs.getString(8))))
                .list();
        return jdbc.sql("""
                select id, code, owner_module, purpose, name_vi, name_en, legal_entity_id, numbering_sequence_code,
                       status from core.document_template where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id)
                .query((rs, n) -> new TemplateView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class),
                        rs.getString(8), rs.getString(9), versions))
                .single();
    }

    private DocumentTemplateDefinition definition(String code) {
        return registry.find(code).orElseThrow(() -> ApiException.unprocessable("unknown-template-definition",
                "No module registers the document template " + code));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID user() {
        return TenantContext.current().userAccountId();
    }
}
