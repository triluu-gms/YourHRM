package vn.yourhrm.core.internal.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.core.api.Documents.DocumentView;
import vn.yourhrm.core.api.Documents.DownloadLink;
import vn.yourhrm.core.api.Documents.Upload;
import vn.yourhrm.core.internal.application.DocumentService;
import vn.yourhrm.shared.auth.api.PublicEndpoint;

/**
 * The shared attachment API (BR-CORE-100/101, US-CORE-100..102). No fixed permission: each call is checked by the
 * document service against the document type's view / manage permission on the owner record.
 */
@RestController
@RequestMapping("/api/v1/core")
class DocumentController {

    private static final String BY_OWNER = "Access follows the owner record: the document service checks the type's permission";

    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/document-types")
    @PublicEndpoint(reason = "The registered document types (names, limits) are reference data for the upload component")
    public List<DocumentTypeDefinition> types() {
        return documents.documentTypes();
    }

    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PublicEndpoint(reason = BY_OWNER)
    public DocumentView upload(@RequestPart("file") MultipartFile file,
            @RequestParam("documentTypeCode") String documentTypeCode,
            @RequestParam("ownerEntityId") UUID ownerEntityId,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "subjectPersonId", required = false) UUID subjectPersonId,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "confidentiality", required = false) String confidentiality) throws IOException {
        return documents.upload(new Upload(documentTypeCode, ownerEntityId, legalEntityId, subjectPersonId, title,
                confidentiality, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    @PostMapping(path = "/documents/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PublicEndpoint(reason = BY_OWNER)
    public DocumentView addVersion(@PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        return documents.addVersion(id, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @GetMapping("/documents/{id}")
    @PublicEndpoint(reason = BY_OWNER)
    public DocumentView get(@PathVariable UUID id) {
        return documents.get(id);
    }

    @GetMapping("/documents")
    @PublicEndpoint(reason = BY_OWNER)
    public List<DocumentView> list(@RequestParam("ownerEntityType") String ownerEntityType,
            @RequestParam("ownerEntityId") UUID ownerEntityId) {
        return documents.list(ownerEntityType, ownerEntityId);
    }

    /** A signed URL valid for at most 15 minutes (NFR-CORE-010). */
    @PostMapping("/documents/{id}/download-link")
    @PublicEndpoint(reason = BY_OWNER)
    public DownloadLink downloadLink(@PathVariable UUID id,
            @RequestParam(name = "versionNo", required = false) Integer versionNo) {
        return documents.downloadLink(id, versionNo);
    }
}
