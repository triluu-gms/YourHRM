package vn.yourhrm.core.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.DocumentScanResults;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.core.internal.infra.AuditWriter;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.storage.ObjectStorage;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The document service (BR-CORE-100/101, US-CORE-100/102). Metadata and versions in {@code core.document*}; binaries
 * in the SaaS object storage under {@code <tenant>/<module>/<yyyy>/<uuid>}. Uploads are checked (type registered,
 * size limit, media type allowed and matching the file's content) and stay unusable until the storage reports a clean
 * antivirus scan. Access follows the owner record: the document type's permission on the target the owner module
 * resolves. Confidential and restricted downloads are audited.
 */
@Service
public class DocumentService implements Documents, DocumentScanResults {

    public static final String PDF = "application/pdf";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final Set<String> DEFAULT_MEDIA_TYPES = Set.of(PDF, DOCX, XLSX, "image/png", "image/jpeg",
            "text/csv");
    /** NFR-CORE-010: signed URLs live at most 15 minutes. */
    static final Duration LINK_TTL = Duration.ofMinutes(15);
    private static final Map<String, Set<String>> EXTENSIONS = Map.of(PDF, Set.of("pdf"), DOCX, Set.of("docx"),
            XLSX, Set.of("xlsx"), "image/png", Set.of("png"), "image/jpeg", Set.of("jpg", "jpeg"), "text/csv",
            Set.of("csv"), "text/html", Set.of("html", "htm"));
    private static final Set<String> AUDITED = Set.of("CONFIDENTIAL", "RESTRICTED");

    /** Published after a scan result was recorded, for engines waiting on a file (e.g. an import). */
    public record DocumentScanned(UUID tenantId, UUID documentId, int versionNo, String documentTypeCode,
            String scanStatus) {
    }

    /** A document row as stored. */
    record Row(UUID id, String typeCode, String ownerModule, String ownerEntityType, UUID ownerEntityId,
            UUID legalEntityId, String title, String confidentiality, String retentionClass, String status,
            int currentVersionNo) {
    }

    private final JdbcClient jdbc;
    private final DocumentTypeRegistry types;
    private final AuthorizationService authorization;
    private final ObjectProvider<ObjectStorage> storage;
    private final Settings settings;
    private final AuditWriter audit;
    private final Notifications notifications;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;
    private final TransactionTemplate tx;

    public DocumentService(JdbcClient jdbc, DocumentTypeRegistry types, AuthorizationService authorization,
            ObjectProvider<ObjectStorage> storage, Settings settings, AuditWriter audit, Notifications notifications,
            ApplicationEventPublisher events, BusinessClock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.types = types;
        this.authorization = authorization;
        this.storage = storage;
        this.settings = settings;
        this.audit = audit;
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------------------------------------ uploads

    @Override
    @Transactional
    public DocumentView upload(Upload u) {
        DocumentTypeDefinition type = type(u.documentTypeCode());
        requireAccess(type.managePermission(), type.ownerEntityType(), u.ownerEntityId(), u.legalEntityId());
        return get(storeUpload(type, u, true));
    }

    /**
     * Stores an upload without the owner-record check, for Core engines that authorised the caller themselves (the
     * import engine checks the import type's permission). Returns the document id.
     */
    @Transactional
    public UUID storeUpload(DocumentTypeDefinition type, Upload u, boolean scan) {
        String confidentiality = u.confidentiality() == null ? type.defaultConfidentiality() : u.confidentiality();
        if (!DocumentTypeDefinition.CONFIDENTIALITY.contains(confidentiality)) {
            throw ApiException.unprocessable("invalid-confidentiality", "Unknown confidentiality " + confidentiality);
        }
        String title = u.title() == null || u.title().isBlank() ? fileName(u.fileName()) : u.title().strip();
        UUID tenant = TenantContext.current().tenantId();
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into core.document (tenant_id, id, owner_module, owner_entity_type, owner_entity_id,
                       legal_entity_id, subject_person_id, document_type_code, title, confidentiality, retention_class,
                       current_version_no, status, created_by)
                values (:t, :id, :module, :ownerType, :ownerId, :le, :person, :type, :title, :conf, :retention, 0,
                        'PENDING_SCAN', :by)""")
                .param("t", tenant).param("id", id).param("module", type.ownerModule())
                .param("ownerType", type.ownerEntityType()).param("ownerId", u.ownerEntityId())
                .param("le", u.legalEntityId()).param("person", u.subjectPersonId()).param("type", type.code())
                .param("title", title).param("conf", confidentiality).param("retention", type.retentionClass())
                .param("by", TenantContext.current().userAccountId())
                .update();
        storeVersion(type, tenant, id, 1, u.fileName(), u.mediaType(), u.content(), "UPLOAD", null, scan);
        return id;
    }

    @Override
    @Transactional
    public DocumentView addVersion(UUID documentId, String fileName, String mediaType, byte[] content) {
        Row d = row(documentId, true);
        DocumentTypeDefinition type = type(d.typeCode());
        requireAccess(type.managePermission(), d);
        if ("PURGED".equals(d.status()) || "ARCHIVED".equals(d.status())) {
            throw ApiException.conflict("document-closed", "The document is " + d.status());
        }
        int next = jdbc.sql("select coalesce(max(version_no), 0) + 1 from core.document_version where tenant_id = :t and document_id = :d")
                .param("t", TenantContext.current().tenantId()).param("d", documentId).query(Integer.class).single();
        storeVersion(type, TenantContext.current().tenantId(), documentId, next, fileName, mediaType, content, "UPLOAD",
                null, true);
        return get(documentId);
    }

    /**
     * A file the platform produced (a generated document), stored as ACTIVE without a scan. Returns the document id.
     */
    @Transactional
    public UUID storeGenerated(DocumentTypeDefinition type, UUID ownerEntityId, UUID legalEntityId,
            UUID subjectPersonId, String title, String fileName, String mediaType, byte[] content,
            UUID templateVersionId) {
        UUID tenant = TenantContext.current().tenantId();
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into core.document (tenant_id, id, owner_module, owner_entity_type, owner_entity_id,
                       legal_entity_id, subject_person_id, document_type_code, title, confidentiality, retention_class,
                       current_version_no, status, created_by)
                values (:t, :id, :module, :ownerType, :ownerId, :le, :person, :type, :title, :conf, :retention, 1,
                        'ACTIVE', :by)""")
                .param("t", tenant).param("id", id).param("module", type.ownerModule())
                .param("ownerType", type.ownerEntityType()).param("ownerId", ownerEntityId).param("le", legalEntityId)
                .param("person", subjectPersonId).param("type", type.code()).param("title", title)
                .param("conf", type.defaultConfidentiality()).param("retention", type.retentionClass())
                .param("by", TenantContext.current().userAccountId())
                .update();
        storeVersion(type, tenant, id, 1, fileName, mediaType, content, "GENERATED", templateVersionId, false);
        return id;
    }

    private void storeVersion(DocumentTypeDefinition type, UUID tenant, UUID documentId, int versionNo,
            String rawFileName, String mediaType, byte[] content, String source, UUID templateVersionId, boolean scan) {
        String fileName = fileName(rawFileName);
        String media = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).strip();
        if (content.length == 0) {
            throw ApiException.unprocessable("empty-file", "The file is empty");
        }
        if (scan) {
            long limit = settings.longValue(CoreSettings.DOCUMENT_MAX_UPLOAD_MB, Settings.Scope.tenant(), null)
                    * 1024 * 1024;
            if (type.maxSizeBytes() != null) {
                limit = Math.min(limit, type.maxSizeBytes());
            }
            if (content.length > limit) {
                throw ApiException.unprocessable("file-too-large", "The file exceeds " + limit + " bytes",
                        Map.of("limitBytes", limit));
            }
            if (!allowedMediaTypes(type).contains(media)) {
                throw ApiException.unprocessable("media-type-not-allowed",
                        "Files of type " + media + " are not accepted for " + type.code());
            }
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1)
                    .toLowerCase(Locale.ROOT) : "";
            if (EXTENSIONS.containsKey(media) && !EXTENSIONS.get(media).contains(ext)) {
                throw ApiException.unprocessable("file-type-mismatch", "The file name does not match " + media);
            }
            if (!contentMatches(media, content)) {
                throw ApiException.unprocessable("file-type-mismatch", "The file content is not " + media);
            }
        }
        ObjectStorage store = storage();
        String objectKey = tenant + "/" + type.ownerModule() + "/" + clock.today().getYear() + "/" + Ids.newId();
        String sha256 = sha256(content);
        jdbc.sql("""
                insert into core.document_version (tenant_id, id, document_id, version_no, object_key, file_name,
                       content_type, size_bytes, sha256, source, generated_from_template_version_id, scan_status,
                       scanned_at, uploaded_by)
                values (:t, :id, :doc, :no, :key, :name, :media, :size, :sha, :source, :tpl, :scan,
                        case when :scan = 'SKIPPED' then now() end, :by)""")
                .param("t", tenant).param("id", Ids.newId()).param("doc", documentId).param("no", versionNo)
                .param("key", objectKey).param("name", fileName).param("media", media)
                .param("size", (long) content.length).param("sha", sha256).param("source", source)
                .param("tpl", templateVersionId).param("scan", scan ? "PENDING" : "SKIPPED")
                .param("by", TenantContext.current().userAccountId())
                .update();
        store.put(tenant, objectKey, media, content, scan);
    }

    // --------------------------------------------------------------------------------------------- scan results

    @Override
    public void report(UUID tenantId, String objectKey, String status) {
        if (!Set.of("CLEAN", "INFECTED", "ERROR").contains(status)) {
            throw ApiException.unprocessable("invalid-scan-status", "Scan status is CLEAN, INFECTED or ERROR");
        }
        TenantContext ctx = new TenantContext(tenantId, null, "saas-object-storage", null, null);
        TenantContext.runWith(ctx, () -> {
            DocumentScanned scanned = tx.execute(s -> applyScan(tenantId, objectKey, status));
            if (scanned != null) {
                events.publishEvent(scanned);
            }
        });
    }

    private DocumentScanned applyScan(UUID tenant, String objectKey, String status) {
        record V(UUID documentId, int versionNo, String scanStatus, String fileName, UUID uploadedBy) {
        }
        Optional<V> v = jdbc.sql("""
                select document_id, version_no, scan_status, file_name, uploaded_by from core.document_version
                 where tenant_id = :t and object_key = :k for update""")
                .param("t", tenant).param("k", objectKey)
                .query((rs, n) -> new V(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class)))
                .optional();
        if (v.isEmpty()) {
            throw ApiException.notFound("Document version with key", objectKey);
        }
        if (!"PENDING".equals(v.get().scanStatus()) && !"ERROR".equals(v.get().scanStatus())) {
            return null; // already decided: repeated reports are ignored
        }
        jdbc.sql("update core.document_version set scan_status = :s, scanned_at = now() where tenant_id = :t and object_key = :k")
                .param("s", status).param("t", tenant).param("k", objectKey).update();
        Row d = row(v.get().documentId(), true);
        if ("CLEAN".equals(status) && v.get().versionNo() > d.currentVersionNo()) {
            jdbc.sql("""
                    update core.document set current_version_no = :no,
                           status = case when status in ('PENDING_SCAN', 'QUARANTINED') then 'ACTIVE' else status end
                     where tenant_id = :t and id = :id""")
                    .param("no", v.get().versionNo()).param("t", tenant).param("id", d.id()).update();
        } else if ("INFECTED".equals(status)) {
            if (d.currentVersionNo() == 0) {
                jdbc.sql("update core.document set status = 'QUARANTINED' where tenant_id = :t and id = :id")
                        .param("t", tenant).param("id", d.id()).update();
            }
            audit.append(new AuditWriter.Event(tenant, "SYSTEM", null, null, null, "QUARANTINE", "core", "document",
                    d.id(), d.legalEntityId(), Map.of("versionNo", String.valueOf(v.get().versionNo()),
                            "objectKey", objectKey), false));
            if (v.get().uploadedBy() != null) {
                notifications.send(new Notifications.SendRequest(CoreNotificationTemplates.DOCUMENT_QUARANTINED,
                        "core", Notifications.Recipients.users(v.get().uploadedBy()),
                        Map.of("title", d.title(), "fileName", v.get().fileName()), "document", d.id(),
                        "/documents/" + d.id(), "quarantine:" + objectKey, null));
            }
        }
        return new DocumentScanned(tenant, d.id(), v.get().versionNo(), d.typeCode(), status);
    }

    // ------------------------------------------------------------------------------------------------- reads

    @Override
    @Transactional(readOnly = true)
    public DocumentView get(UUID documentId) {
        Row d = row(documentId, false);
        requireAccess(type(d.typeCode()).viewPermission(), d);
        return view(d);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentView> list(String ownerEntityType, UUID ownerEntityId) {
        List<Row> rows = jdbc.sql(ROW_SQL + " where tenant_id = :t and owner_entity_type = :type and owner_entity_id = :id and status <> 'PURGED' order by created_at")
                .param("t", TenantContext.current().tenantId()).param("type", ownerEntityType).param("id", ownerEntityId)
                .query(DocumentService::mapRow).list();
        return rows.stream().filter(d -> types.find(d.typeCode()).isPresent()
                        && canAccess(types.find(d.typeCode()).get().viewPermission(), d))
                .map(this::view).toList();
    }

    @Override
    @Transactional
    public DownloadLink downloadLink(UUID documentId, Integer versionNo) {
        Row d = row(documentId, false);
        requireAccess(type(d.typeCode()).viewPermission(), d);
        int no = versionNo == null ? d.currentVersionNo() : versionNo;
        record V(String key, String fileName, String media, String scan) {
        }
        V v = jdbc.sql("""
                select object_key, file_name, content_type, scan_status from core.document_version
                 where tenant_id = :t and document_id = :d and version_no = :no""")
                .param("t", TenantContext.current().tenantId()).param("d", documentId).param("no", no)
                .query((rs, n) -> new V(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "document-not-ready",
                        "The document has no usable file yet (antivirus scan pending or failed)"));
        if (!"CLEAN".equals(v.scan()) && !"SKIPPED".equals(v.scan())) {
            throw ApiException.conflict("document-not-ready", "The file's antivirus scan is " + v.scan());
        }
        if (AUDITED.contains(d.confidentiality())) {
            audit.append(AuditWriter.Event.ofCurrentActor("DOWNLOAD", d.ownerModule(), "document", d.id(),
                    d.legalEntityId(), Map.of("versionNo", String.valueOf(no), "confidentiality", d.confidentiality(),
                            "documentType", d.typeCode()), true));
        }
        Instant expires = clock.now().plus(LINK_TTL);
        return new DownloadLink(storage().signedDownloadUrl(TenantContext.current().tenantId(), v.key(), v.fileName(),
                v.media(), LINK_TTL), expires, v.fileName());
    }

    /** The bytes of a clean (or generated) version, for Core engines; no access check. */
    @Transactional(readOnly = true)
    public byte[] content(UUID documentId, int versionNo) {
        String key = jdbc.sql("""
                select object_key from core.document_version
                 where tenant_id = :t and document_id = :d and version_no = :no and scan_status in ('CLEAN', 'SKIPPED')""")
                .param("t", TenantContext.current().tenantId()).param("d", documentId).param("no", versionNo)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.conflict("document-not-ready", "No clean file " + documentId));
        return storage().get(TenantContext.current().tenantId(), key);
    }

    /** Metadata of a document without the access check, for Core engines. */
    @Transactional(readOnly = true)
    public Optional<DocumentView> find(UUID documentId) {
        return jdbc.sql(ROW_SQL + " where tenant_id = :t and id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", documentId)
                .query(DocumentService::mapRow).optional().map(this::view);
    }

    public List<DocumentTypeDefinition> documentTypes() {
        return types.all();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static final String ROW_SQL = """
            select id, document_type_code, owner_module, owner_entity_type, owner_entity_id, legal_entity_id, title,
                   confidentiality, retention_class, status, current_version_no
              from core.document""";

    private static Row mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("document_type_code"), rs.getString("owner_module"),
                rs.getString("owner_entity_type"), rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("legal_entity_id", UUID.class), rs.getString("title"), rs.getString("confidentiality"),
                rs.getString("retention_class"), rs.getString("status"), rs.getInt("current_version_no"));
    }

    private Row row(UUID id, boolean forUpdate) {
        return jdbc.sql(ROW_SQL + " where tenant_id = :t and id = :id" + (forUpdate ? " for update" : ""))
                .param("t", TenantContext.current().tenantId()).param("id", id)
                .query(DocumentService::mapRow).optional()
                .orElseThrow(() -> ApiException.notFound("Document", id));
    }

    private DocumentView view(Row d) {
        List<DocumentVersionView> versions = jdbc.sql("""
                select version_no, file_name, content_type, size_bytes, sha256, source, scan_status, created_at,
                       uploaded_by
                  from core.document_version where tenant_id = :t and document_id = :d order by version_no""")
                .param("t", TenantContext.current().tenantId()).param("d", d.id())
                .query((rs, n) -> new DocumentVersionView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getObject(8, OffsetDateTime.class).toInstant(), rs.getObject(9, UUID.class)))
                .list();
        return new DocumentView(d.id(), d.typeCode(), d.ownerModule(), d.ownerEntityType(), d.ownerEntityId(),
                d.legalEntityId(), d.title(), d.confidentiality(), d.retentionClass(), d.status(), d.currentVersionNo(),
                versions);
    }

    private DocumentTypeDefinition type(String code) {
        return types.find(code).orElseThrow(() -> ApiException.unprocessable("unknown-document-type",
                "Document type " + code + " is not registered"));
    }

    private void requireAccess(String permission, Row d) {
        requireAccess(permission, d.ownerEntityType(), d.ownerEntityId(), d.legalEntityId());
    }

    private void requireAccess(String permission, String ownerEntityType, UUID ownerEntityId, UUID legalEntityId) {
        if (!canAccess(permission, ownerEntityType, ownerEntityId, legalEntityId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "document-access-denied",
                    "Access to the document's owner record is not granted");
        }
    }

    private boolean canAccess(String permission, Row d) {
        return canAccess(permission, d.ownerEntityType(), d.ownerEntityId(), d.legalEntityId());
    }

    /** The owner module's resolver decides the target; without one the legal entity, else the tenant. */
    private boolean canAccess(String permission, String ownerEntityType, UUID ownerEntityId, UUID legalEntityId) {
        Optional<AuthorizationTarget> target = types.resolver(ownerEntityType)
                .map(r -> ownerEntityId == null ? Optional.<AuthorizationTarget>empty()
                        : r.target(ownerEntityType, ownerEntityId))
                .orElseGet(() -> Optional.of(legalEntityId != null ? new AuthorizationTarget.LegalEntity(legalEntityId)
                        : AuthorizationTarget.tenant()));
        return target.isPresent() && authorization.can(permission, target.get());
    }

    @SuppressWarnings("unchecked")
    private Set<String> allowedMediaTypes(DocumentTypeDefinition type) {
        Object tenantList = settings.value(CoreSettings.DOCUMENT_MEDIA_TYPES, Settings.Scope.tenant(), null);
        Set<String> tenantAllowed = tenantList instanceof List<?> l
                ? Set.copyOf((List<String>) l) : DEFAULT_MEDIA_TYPES;
        if (type.mediaTypes().isEmpty()) {
            return tenantAllowed;
        }
        return type.mediaTypes();
    }

    /** Magic-number check of the declared media type (the name's extension alone proves nothing). */
    static boolean contentMatches(String media, byte[] c) {
        return switch (media) {
            case PDF -> startsWith(c, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "image/png" -> startsWith(c, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            case "image/jpeg" -> startsWith(c, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case DOCX, XLSX -> startsWith(c, new byte[] {'P', 'K', 3, 4});
            case "text/csv", "text/html", "text/plain" -> {
                for (int i = 0; i < Math.min(c.length, 4096); i++) {
                    if (c[i] == 0) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> true;
        };
    }

    private static boolean startsWith(byte[] c, byte[] prefix) {
        if (c.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (c[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    static String fileName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            throw ApiException.unprocessable("invalid-file-name", "The file needs a name");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    ObjectStorage storage() {
        ObjectStorage s = storage.getIfAvailable();
        if (s == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable",
                    "The object storage is not available");
        }
        return s;
    }
}
