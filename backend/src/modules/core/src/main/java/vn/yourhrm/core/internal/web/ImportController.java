package vn.yourhrm.core.internal.web;

import java.io.IOException;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.yourhrm.core.internal.application.DocumentService;
import vn.yourhrm.core.internal.application.ImportService;
import vn.yourhrm.core.internal.application.ImportService.ImportTypeView;
import vn.yourhrm.core.internal.application.ImportService.JobView;
import vn.yourhrm.core.internal.application.ImportService.RowView;
import vn.yourhrm.shared.auth.api.PublicEndpoint;

/**
 * The import engine (BR-CORE-110..112/114, US-CORE-110..117): templates, upload (validation runs as a dry-run
 * first), progress, preview grid, error file, commit, rollback, and the opening-balance reconciliation, waivers and
 * sign-off. Each import type declares its own permission, which the engine checks on every call.
 */
@RestController
@RequestMapping("/api/v1/core/imports")
class ImportController {

    private static final String BY_TYPE = "Each import type declares its permission; the import engine checks it";

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    record WaiveRequest(String reason) {
    }

    @GetMapping("/types")
    @PublicEndpoint(reason = "Lists only the import types whose permission the caller holds")
    public List<ImportTypeView> types() {
        return imports.types();
    }

    @GetMapping("/types/{code}/template")
    @PublicEndpoint(reason = BY_TYPE)
    public ResponseEntity<byte[]> template(@PathVariable String code,
            @RequestParam(name = "lang", defaultValue = "vi") String lang) {
        return file(imports.template(code, "en".equals(lang)), code + "-template.xlsx");
    }

    @PostMapping(path = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PublicEndpoint(reason = BY_TYPE)
    public JobView upload(@RequestPart("file") MultipartFile file, @RequestParam("importType") String importType,
            @RequestParam(name = "mode", required = false) String mode,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "asOfDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate)
            throws IOException {
        return imports.create(new ImportService.Upload(importType, mode, legalEntityId, asOfDate,
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    @GetMapping("/jobs")
    @PublicEndpoint(reason = BY_TYPE)
    public List<JobView> list(@RequestParam(name = "importType", required = false) String importType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return imports.list(importType, status, from, to);
    }

    @GetMapping("/jobs/{id}")
    @PublicEndpoint(reason = BY_TYPE)
    public JobView get(@PathVariable UUID id) {
        return imports.get(id);
    }

    @GetMapping("/jobs/{id}/rows")
    @PublicEndpoint(reason = BY_TYPE)
    public List<RowView> rows(@PathVariable UUID id, @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        return imports.rows(id, status, page, size);
    }

    @GetMapping("/jobs/{id}/error-file")
    @PublicEndpoint(reason = BY_TYPE)
    public ResponseEntity<byte[]> errorFile(@PathVariable UUID id,
            @RequestParam(name = "lang", defaultValue = "vi") String lang) {
        return file(imports.errorFile(id, "en".equals(lang)), "import-" + id + "-errors.xlsx");
    }

    @GetMapping("/jobs/{id}/reconciliation-report")
    @PublicEndpoint(reason = BY_TYPE)
    public ResponseEntity<byte[]> reconciliationReport(@PathVariable UUID id,
            @RequestParam(name = "lang", defaultValue = "vi") String lang) {
        return file(imports.reconciliationReport(id, "en".equals(lang)), "import-" + id + "-reconciliation.xlsx");
    }

    @PostMapping("/jobs/{id}/commit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PublicEndpoint(reason = BY_TYPE)
    public JobView commit(@PathVariable UUID id) {
        return imports.commit(id);
    }

    @PostMapping("/jobs/{id}/cancel")
    @PublicEndpoint(reason = BY_TYPE)
    public JobView cancel(@PathVariable UUID id) {
        return imports.cancel(id);
    }

    @PostMapping("/jobs/{id}/rollback")
    @PublicEndpoint(reason = BY_TYPE)
    public JobView rollback(@PathVariable UUID id) {
        return imports.rollback(id);
    }

    @PostMapping("/jobs/{id}/reconciliation/{reconciliationId}/waive")
    @PublicEndpoint(reason = BY_TYPE)
    public JobView waive(@PathVariable UUID id, @PathVariable UUID reconciliationId, @RequestBody WaiveRequest r) {
        return imports.waive(id, reconciliationId, r.reason());
    }

    @PostMapping("/jobs/{id}/sign-off")
    @PublicEndpoint(reason = BY_TYPE)
    public JobView signOff(@PathVariable UUID id) {
        return imports.signOff(id);
    }

    private static ResponseEntity<byte[]> file(byte[] content, String name) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(DocumentService.XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(content);
    }
}
