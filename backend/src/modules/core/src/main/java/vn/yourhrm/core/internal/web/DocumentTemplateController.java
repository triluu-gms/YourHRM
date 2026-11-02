package vn.yourhrm.core.internal.web;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.DocumentTemplateDefinition;
import vn.yourhrm.core.internal.application.DocumentTemplateService;
import vn.yourhrm.core.internal.application.DocumentTemplateService.Rendered;
import vn.yourhrm.core.internal.application.DocumentTemplateService.TemplateView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Tenant document templates (BR-CORE-102, US-CORE-104): registered definitions and merge fields, templates per legal
 * entity, DOCX / HTML versions checked against the merge-field registry, preview with sample data, publish; and the
 * legal entity's letterhead and seal. Template administration is tenant-wide.
 */
@RestController
@RequestMapping("/api/v1/core")
class DocumentTemplateController {

    private final DocumentTemplateService templates;

    DocumentTemplateController(DocumentTemplateService templates) {
        this.templates = templates;
    }

    record CreateTemplateRequest(String code, UUID legalEntityId, String numberingSequenceCode) {
    }

    record LetterheadRequest(UUID letterheadDocumentId, UUID sealDocumentId) {
    }

    @GetMapping("/document-template-definitions")
    @RequiresPermission(value = CorePermissions.DOCUMENT_TEMPLATE_VIEW, tenantWide = true)
    public List<DocumentTemplateDefinition> definitions() {
        return templates.definitions();
    }

    @GetMapping("/document-templates")
    @RequiresPermission(value = CorePermissions.DOCUMENT_TEMPLATE_VIEW, tenantWide = true)
    public List<TemplateView> list() {
        return templates.list();
    }

    @PostMapping("/document-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(value = CorePermissions.DOCUMENT_TEMPLATE_MANAGE, tenantWide = true)
    public TemplateView create(@RequestBody CreateTemplateRequest r) {
        return templates.create(r.code(), r.legalEntityId(), r.numberingSequenceCode());
    }

    @PostMapping(path = "/document-templates/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(value = CorePermissions.DOCUMENT_TEMPLATE_MANAGE, tenantWide = true)
    public TemplateView uploadVersion(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
            @RequestParam(name = "validFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
            @RequestParam(name = "outputFormat", required = false) String outputFormat) throws IOException {
        return templates.uploadVersion(id, file.getOriginalFilename(), file.getBytes(), validFrom, outputFormat);
    }

    @PostMapping("/document-templates/{id}/versions/{versionId}/preview")
    @RequiresPermission(value = CorePermissions.DOCUMENT_TEMPLATE_VIEW, tenantWide = true)
    public ResponseEntity<byte[]> preview(@PathVariable UUID id, @PathVariable UUID versionId) {
        Rendered r = templates.preview(id, versionId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview." + r.fileExtension() + "\"")
                .body(r.content());
    }

    @PostMapping("/document-templates/{id}/versions/{versionId}/publish")
    @RequiresPermission(value = CorePermissions.DOCUMENT_TEMPLATE_MANAGE, tenantWide = true)
    public TemplateView publish(@PathVariable UUID id, @PathVariable UUID versionId) {
        return templates.publish(id, versionId);
    }

    /** Images of the legal entity's own LE_LETTERHEAD / LE_SEAL documents; the service checks the LE scope. */
    @PutMapping("/legal-entities/{id}/letterhead")
    @RequiresPermission(CorePermissions.LEGAL_ENTITY_UPDATE)
    public ResponseEntity<Void> letterhead(@PathVariable UUID id, @RequestBody LetterheadRequest r) {
        templates.setLetterhead(id, r.letterheadDocumentId(), r.sealDocumentId());
        return ResponseEntity.noContent().build();
    }
}
