package vn.yourhrm.core.internal.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.CoreEvents.ImportRolledBack;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.core.api.ImportType.Column;
import vn.yourhrm.core.api.ImportType.CommittedRow;
import vn.yourhrm.core.api.ImportType.ControlCheck;
import vn.yourhrm.core.api.ImportType.Definition;
import vn.yourhrm.core.api.ImportType.ImportContext;
import vn.yourhrm.core.internal.application.DocumentService.DocumentScanned;
import vn.yourhrm.core.internal.application.ImportProcessor.Job;
import vn.yourhrm.core.internal.application.ImportProcessor.Staged;
import vn.yourhrm.core.internal.infra.AuditWriter;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.core.internal.infra.ImportWorkbooks;
import vn.yourhrm.core.internal.infra.ImportWorkbooks.ErrorRow;
import vn.yourhrm.core.internal.infra.ImportWorkbooks.ReconciliationLine;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.onboarding.OpeningBalanceLocks;
import vn.yourhrm.shared.common.tenancy.RequestOrigin;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The import engine's jobs (BR-CORE-110..112/114, US-CORE-110..117). Upload stores the file through the document
 * service; once its antivirus scan is clean, parsing and the dry-run validation run on a worker (virtual threads; the
 * SaaS job queue takes this over later). Commit and the opening-balance flow (reconciliation, waivers, sign-off) are
 * explicit steps; a committed job can be rolled back within 24 hours. Every step runs as the requesting user with the
 * IMPORT channel and the job id as correlation id, so the row audit of the owner module's tables names the job.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    static final Duration ROLLBACK_WINDOW = Duration.ofHours(24);

    public record ImportTypeView(String code, String ownerModule, String nameVi, String nameEn, String templateVersion,
            boolean partialCommit, boolean openingBalance, List<String> dependsOn, List<Column> columns,
            List<String> businessKey, List<ControlCheck> controlChecks) {
    }

    public record ReconciliationView(UUID id, String checkCode, String grainKey, BigDecimal controlValue,
            BigDecimal stagedValue, BigDecimal committedValue, BigDecimal tolerance, String status,
            String waiverReason) {
    }

    public record JobView(UUID id, String importType, String ownerModule, String mode, String status,
            UUID legalEntityId, LocalDate asOfDate, UUID fileDocumentId, String fileSha256, int rowCount,
            int errorCount, int warningCount, int processedCount, String failureReason, UUID dependsOnJobId,
            Instant createdAt, UUID createdBy, Instant committedAt, Instant rollbackUntil, Instant rolledBackAt,
            UUID signedOffBy, Instant signedOffAt, List<ReconciliationView> reconciliation) {
    }

    public record RowView(int rowNo, String status, String businessKey, Map<String, Object> preview,
            List<Map<String, Object>> messages, String targetEntity, UUID targetId, String commitAction) {
    }

    public record Upload(String importType, String mode, UUID legalEntityId, LocalDate asOfDate, String fileName,
            String mediaType, byte[] content) {
    }

    private final JdbcClient jdbc;
    private final ImportTypeRegistry registry;
    private final ImportProcessor processor;
    private final ImportLookups lookups;
    private final ImportWorkbooks workbooks;
    private final DocumentService documents;
    private final DocumentTypeRegistry documentTypes;
    private final AuthorizationService authorization;
    private final ObjectProvider<OpeningBalanceLocks> locks;
    private final AuditWriter audit;
    private final CoreJson json;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;
    private final TransactionTemplate tx;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ImportService(JdbcClient jdbc, ImportTypeRegistry registry, ImportProcessor processor, ImportLookups lookups,
            ImportWorkbooks workbooks, DocumentService documents, DocumentTypeRegistry documentTypes,
            AuthorizationService authorization, ObjectProvider<OpeningBalanceLocks> locks, AuditWriter audit,
            CoreJson json, ApplicationEventPublisher events, BusinessClock clock,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.processor = processor;
        this.lookups = lookups;
        this.workbooks = workbooks;
        this.documents = documents;
        this.documentTypes = documentTypes;
        this.authorization = authorization;
        this.locks = locks;
        this.audit = audit;
        this.json = json;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // --------------------------------------------------------------------------------------- types, templates

    /** The import types the caller may run (US-CORE-110). */
    public List<ImportTypeView> types() {
        return registry.all().stream().map(ImportType::definition)
                .filter(d -> authorization.hasPermission(d.permission()))
                .map(d -> new ImportTypeView(d.code(), d.ownerModule(), d.nameVi(), d.nameEn(), d.templateVersion(),
                        d.partialCommit(), d.openingBalance(), d.dependsOn(),
                        ImportWorkbooks.withExternalRef(d.columns()), d.businessKey(), d.controlChecks()))
                .toList();
    }

    /** The tenant's Excel template: dropdowns from its own code lists and records (US-CORE-115). */
    @Transactional(readOnly = true)
    public byte[] template(String code, boolean english) {
        Definition d = runnable(code).definition();
        Map<String, List<String>> dropdowns = new HashMap<>();
        for (Column c : d.columns()) {
            if (c.lookup() != null) {
                dropdowns.put(c.key(), lookups.entries(c.lookup(), clock.today()).stream().limit(5000)
                        .map(e -> e.names().isEmpty() ? e.code() : e.code() + " - " + e.names().getFirst()).toList());
            }
        }
        return workbooks.template(d, english, dropdowns);
    }

    // ------------------------------------------------------------------------------------------------- jobs

    /** Stores the file and creates the job; parsing and validation start when the file's scan is clean. */
    @Transactional
    public JobView create(Upload u) {
        ImportType type = runnable(u.importType());
        Definition d = type.definition();
        if (u.legalEntityId() != null
                && !authorization.can(d.permission(), new AuthorizationTarget.LegalEntity(u.legalEntityId()))) {
            throw ApiException.notFound("Legal entity", u.legalEntityId());
        }
        String mode = u.mode() == null ? "STANDARD" : u.mode();
        UUID tenant = TenantContext.current().tenantId();
        UUID dependsOn = null;
        if ("OPENING_BALANCE".equals(mode)) {
            if (!d.openingBalance()) {
                throw ApiException.unprocessable("opening-balance-not-supported",
                        "Import " + d.code() + " has no opening-balance mode");
            }
            if (u.asOfDate() == null) {
                throw ApiException.unprocessable("as-of-date-required",
                        "An opening-balance import needs the go-live date (as of)");
            }
            OpeningBalanceLocks l = locks.getIfAvailable();
            if (l != null && l.isLocked(tenant, u.legalEntityId())) {
                throw ApiException.conflict("opening-balance-locked",
                        "Opening balances are locked for this legal entity (it has gone live)");
            }
            for (String dep : d.dependsOn()) {
                UUID depJob = jdbc.sql("""
                        select id from core.import_job
                         where tenant_id = :t and import_type = :type and mode = 'OPENING_BALANCE'
                           and status in ('COMMITTED', 'PURGED')
                           and (legal_entity_id is null or cast(:le as uuid) is null or legal_entity_id = :le)
                         order by committed_at desc limit 1""")
                        .param("t", tenant).param("type", dep).param("le", u.legalEntityId())
                        .query(UUID.class).optional()
                        .orElseThrow(() -> ApiException.conflict("dependency-not-committed",
                                "Load " + dep + " first: " + d.code() + " depends on it"));
                dependsOn = dependsOn == null ? depJob : dependsOn;
            }
        } else if (!"STANDARD".equals(mode)) {
            throw ApiException.unprocessable("invalid-mode", "Mode is STANDARD or OPENING_BALANCE");
        }
        String media = u.mediaType() == null ? "" : u.mediaType().toLowerCase(java.util.Locale.ROOT);
        String name = u.fileName() == null ? "" : u.fileName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".csv")) {
            media = "text/csv";
        } else if (name.endsWith(".xlsx")) {
            media = DocumentService.XLSX;
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into core.import_job (tenant_id, id, import_type, owner_module, mode, template_version,
                       legal_entity_id, as_of_date, depends_on_job_id, status, created_by)
                values (:t, :id, :type, :module, :mode, :ver, :le, :asOf, :dep, 'UPLOADED', :by)""")
                .param("t", tenant).param("id", id).param("type", d.code()).param("module", d.ownerModule())
                .param("mode", mode).param("ver", d.templateVersion()).param("le", u.legalEntityId())
                .param("asOf", u.asOfDate()).param("dep", dependsOn)
                .param("by", TenantContext.current().userAccountId())
                .update();
        UUID file = documents.storeUpload(documentTypes.find(CoreDocumentTypes.IMPORT_FILE).orElseThrow(),
                new vn.yourhrm.core.api.Documents.Upload(CoreDocumentTypes.IMPORT_FILE, id, u.legalEntityId(), null,
                        d.code(), null, u.fileName(), media, u.content()), true);
        jdbc.sql("""
                update core.import_job set file_document_id = :doc,
                       file_sha256 = (select sha256 from core.document_version where tenant_id = :t and document_id = :doc)
                 where tenant_id = :t and id = :id""")
                .param("doc", file).param("t", tenant).param("id", id).update();
        return get(id);
    }

    /** The file of a job came back from the antivirus scan: parse and validate it, or fail the job. */
    @EventListener
    public void onScanned(DocumentScanned e) {
        if (!CoreDocumentTypes.IMPORT_FILE.equals(e.documentTypeCode()) || e.versionNo() != 1) {
            return;
        }
        TenantContext system = new TenantContext(e.tenantId(), null, "import-engine", null, null);
        TenantContext.runWith(system, () -> {
            record Owner(UUID jobId, UUID createdBy) {
            }
            Owner owner = tx.execute(s -> jdbc.sql("""
                    select id, created_by from core.import_job where tenant_id = :t and file_document_id = :doc""")
                    .param("t", e.tenantId()).param("doc", e.documentId())
                    .query((rs, n) -> new Owner(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                    .optional().orElse(null));
            if (owner == null) {
                return;
            }
            TenantContext asCreator = new TenantContext(e.tenantId(), owner.createdBy(), "import-engine",
                    owner.jobId().toString(), null);
            if (!"CLEAN".equals(e.scanStatus())) {
                TenantContext.runWith(asCreator, () -> processor.fail(owner.jobId(), ApiException.unprocessable(
                        "file-not-clean", "The file's antivirus scan result is " + e.scanStatus())));
                return;
            }
            dispatch(asCreator, owner.jobId(), () -> processor.parseAndValidate(owner.jobId()));
        });
    }

    /** All-or-nothing commit on a worker (US-CORE-112); refused while rows have errors unless partial commit. */
    @Transactional
    public JobView commit(UUID jobId) {
        Job job = processor.job(jobId, true);
        Definition d = runnable(job.importType()).definition();
        requireLegalEntity(d, job);
        String expected = "OPENING_BALANCE".equals(job.mode()) ? "SIGNED_OFF" : "VALIDATED";
        if (!expected.equals(job.status())) {
            throw ApiException.conflict("job-not-committable", "The job is " + job.status() + "; it must be " + expected);
        }
        int errors = jdbc.sql("select error_count from core.import_job where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", jobId).query(Integer.class).single();
        if (errors > 0 && !d.partialCommit()) {
            throw ApiException.conflict("job-has-errors", errors
                    + " rows have errors; fix the file and upload it again (this import commits all rows or none)");
        }
        jdbc.sql("update core.import_job set status = 'COMMITTING', processed_count = 0 where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", jobId).update();
        TenantContext ctx = TenantContext.current();
        afterCommit(() -> dispatch(ctx, jobId, () -> processor.commit(jobId)));
        return get(jobId);
    }

    /** Stops a job that has not been committed. */
    @Transactional
    public JobView cancel(UUID jobId) {
        Job job = processor.job(jobId, true);
        requireLegalEntity(runnable(job.importType()).definition(), job);
        if (List.of("COMMITTING", "COMMITTED", "ROLLED_BACK", "CANCELLED", "PURGED").contains(job.status())) {
            throw ApiException.conflict("job-not-cancellable", "The job is " + job.status());
        }
        jdbc.sql("update core.import_job set status = 'CANCELLED' where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", jobId).update();
        return get(jobId);
    }

    /**
     * Undoes a committed job within 24 hours (US-CORE-113) when the owner module finds no dependent data; otherwise
     * explains what depends on it.
     */
    @Transactional
    public JobView rollback(UUID jobId) {
        Job job = processor.job(jobId, true);
        ImportType type = runnable(job.importType());
        requireLegalEntity(type.definition(), job);
        Instant committedAt = jdbc.sql("select committed_at from core.import_job where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", jobId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).optional()
                .map(o -> o == null ? null : o.toInstant()).orElse(null);
        if (!"COMMITTED".equals(job.status()) || committedAt == null) {
            throw ApiException.conflict("job-not-committed", "Only a committed job can be rolled back");
        }
        if (clock.now().isAfter(committedAt.plus(ROLLBACK_WINDOW))) {
            throw ApiException.conflict("rollback-window-expired",
                    "A job can be rolled back only within 24 hours of its commit; correct the data instead");
        }
        byte[] key = processor.unwrap(job);
        List<CommittedRow> rows = new ArrayList<>();
        int after = 0;
        while (true) {
            List<Staged> page = processor.page(jobId, key, after, java.util.Set.of("COMMITTED"));
            if (page.isEmpty()) {
                break;
            }
            for (Staged s : page) {
                rows.add(new CommittedRow(s.rowNo(), s.businessKey(), new HashMap<>(s.raw()), s.targetEntity(),
                        s.targetId(), s.action()));
            }
            after = page.getLast().rowNo();
        }
        ImportContext ctx = new ImportContext(tenant(), jobId, job.importType(), job.mode(), job.legalEntityId(),
                job.asOfDate(), java.util.Set.of());
        List<String> blockers = type.undoBlockers(ctx, rows);
        if (!blockers.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "rollback-blocked",
                    "The import cannot be rolled back: other data depends on it", Map.of("blockers", blockers));
        }
        RequestOrigin outer = RequestOrigin.optional().orElse(null);
        RequestOrigin origin = new RequestOrigin(outer == null ? RequestOrigin.SYSTEM : outer.channel(),
                outer == null ? null : outer.clientIp(), outer == null ? null : outer.userAgent(), jobId.toString());
        RequestOrigin.runWith(origin, () -> type.undo(ctx, rows));
        jdbc.sql("update core.import_job set status = 'ROLLED_BACK', rolled_back_at = now() where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", jobId).update();
        audit.append(AuditWriter.Event.ofCurrentActor("IMPORT_ROLLBACK", type.definition().ownerModule(), "import_job",
                jobId, job.legalEntityId(), Map.of("importType", job.importType(), "rows", String.valueOf(rows.size())),
                false));
        Definition d = type.definition();
        afterCommit(() -> events.publishEvent(new ImportRolledBack(ctx.tenantId(), jobId, d.code(), d.ownerModule())));
        return get(jobId);
    }

    // ------------------------------------------------------------------------------------ opening-balance gate

    /** Accepts a reconciliation difference with a reason (ADR-0037 §3). */
    @Transactional
    public JobView waive(UUID jobId, UUID reconciliationId, String reason) {
        Job job = processor.job(jobId, true);
        requireLegalEntity(runnable(job.importType()).definition(), job);
        if (!"RECONCILED".equals(job.status())) {
            throw ApiException.conflict("job-not-reconciled", "Differences are waived before sign-off");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("waiver-reason-required", "A waiver needs a reason");
        }
        int n = jdbc.sql("""
                update core.import_reconciliation set status = 'WAIVED', waiver_reason = :r, waived_by = :by,
                       updated_by = :by
                 where tenant_id = :t and import_job_id = :job and id = :id and status = 'MISMATCH'""")
                .param("r", reason.strip()).param("by", TenantContext.current().userAccountId()).param("t", tenant())
                .param("job", jobId).param("id", reconciliationId).update();
        if (n == 0) {
            throw ApiException.notFound("Reconciliation difference", reconciliationId);
        }
        return get(jobId);
    }

    /** The customer's sign-off of the reconciliation: no unexplained difference and no row in error. */
    @Transactional
    public JobView signOff(UUID jobId) {
        Job job = processor.job(jobId, true);
        Definition d = runnable(job.importType()).definition();
        requireLegalEntity(d, job);
        if (!"RECONCILED".equals(job.status())) {
            throw ApiException.conflict("job-not-reconciled", "The job is " + job.status());
        }
        int open = jdbc.sql("select count(*) from core.import_reconciliation where tenant_id = :t and import_job_id = :id and status = 'MISMATCH'")
                .param("t", tenant()).param("id", jobId).query(Integer.class).single();
        if (open > 0) {
            throw ApiException.conflict("reconciliation-open", open + " differences must be fixed or waived first");
        }
        int errors = jdbc.sql("select error_count from core.import_job where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", jobId).query(Integer.class).single();
        if (errors > 0 && !d.partialCommit()) {
            throw ApiException.conflict("job-has-errors", errors + " rows have errors");
        }
        jdbc.sql("""
                update core.import_job set status = 'SIGNED_OFF', signed_off_by = :by, signed_off_at = now()
                 where tenant_id = :t and id = :id""")
                .param("by", TenantContext.current().userAccountId()).param("t", tenant()).param("id", jobId).update();
        return get(jobId);
    }

    // --------------------------------------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public JobView get(UUID jobId) {
        JobView v = view(jobId);
        if (!mayView(v.importType())) {
            throw ApiException.notFound("Import job", jobId);
        }
        return v;
    }

    /** Job history (US-CORE-114), newest first. */
    @Transactional(readOnly = true)
    public List<JobView> list(String importType, String status, Instant from, Instant to) {
        return jdbc.sql("""
                select id, import_type from core.import_job
                 where tenant_id = :t and (cast(:type as text) is null or import_type = :type)
                   and (cast(:status as text) is null or status = :status)
                   and (cast(:from as timestamptz) is null or created_at >= :from)
                   and (cast(:to as timestamptz) is null or created_at < :to)
                 order by created_at desc limit 500""")
                .param("t", tenant()).param("type", importType).param("status", status)
                .param("from", from == null ? null : OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC))
                .param("to", to == null ? null : OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC))
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list().stream()
                .filter(r -> mayView(r[1])).map(r -> view(UUID.fromString(r[0]))).toList();
    }

    /** The preview grid: non-sensitive columns and the row's messages. */
    @Transactional(readOnly = true)
    public List<RowView> rows(UUID jobId, String status, int page, int size) {
        get(jobId);
        return jdbc.sql("""
                select row_no, status, business_key, preview::text, messages::text, target_entity, target_id,
                       commit_action
                  from core.import_row where tenant_id = :t and import_job_id = :id
                   and (cast(:s as text) is null or status = :s)
                 order by row_no limit :lim offset :off""")
                .param("t", tenant()).param("id", jobId).param("s", status).param("lim", Math.min(size, 500))
                .param("off", page * Math.min(size, 500))
                .query((rs, n) -> new RowView(rs.getInt(1), rs.getString(2), rs.getString(3), json.map(rs.getString(4)),
                        json.listOfMaps(rs.getString(5)), rs.getString(6), rs.getObject(7, UUID.class),
                        rs.getString(8)))
                .list();
    }

    /** The user's file with each row's status and messages (US-CORE-114). */
    @Transactional // the download is audited
    public byte[] errorFile(UUID jobId, boolean english) {
        JobView v = get(jobId);
        Job job = processor.job(jobId, false);
        byte[] key = processor.unwrap(job);
        String cols = jdbc.sql("select source_columns::text from core.import_job where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", jobId).query(String.class).optional().orElse(null);
        List<String> headers = json.listOfStrings(cols);
        List<ErrorRow> out = new ArrayList<>();
        int after = 0;
        while (true) {
            List<Staged> page = processor.page(jobId, key, after, null);
            if (page.isEmpty()) {
                break;
            }
            for (Staged s : page) {
                StringBuilder m = new StringBuilder();
                for (Map<String, Object> msg : json.listOfMaps(s.messages())) {
                    if (!m.isEmpty()) {
                        m.append("; ");
                    }
                    m.append(english ? msg.get("messageEn") : msg.get("messageVi"));
                }
                out.add(new ErrorRow(s.rowNo(), s.cells(), s.status(), m.toString()));
            }
            after = page.getLast().rowNo();
        }
        audit.append(AuditWriter.Event.ofCurrentActor("EXPORT", "core", "import_job", jobId, v.legalEntityId(),
                Map.of("file", "error-file", "rows", String.valueOf(out.size())), true));
        return workbooks.errorFile(headers, out, english);
    }

    /** The reconciliation report with sign-off fields (US-CORE-117). */
    @Transactional // the download is audited
    public byte[] reconciliationReport(UUID jobId, boolean english) {
        JobView v = get(jobId);
        Definition d = registry.find(v.importType()).orElseThrow().definition();
        Map<String, String> labels = new HashMap<>();
        labels.put("ROW_COUNT", english ? "Number of rows" : "Số dòng");
        d.controlChecks().forEach(c -> labels.put(c.code(), english ? c.labelEn() : c.labelVi()));
        List<String[]> header = new ArrayList<>();
        header.add(new String[] {english ? "Import" : "Loại import", d.code() + " - " + (english ? d.nameEn() : d.nameVi())});
        header.add(new String[] {english ? "Job" : "Lần import", v.id().toString()});
        header.add(new String[] {english ? "As of (go-live)" : "Ngày go-live", String.valueOf(v.asOfDate())});
        header.add(new String[] {english ? "Status" : "Trạng thái", v.status()});
        header.add(new String[] {english ? "Rows / errors" : "Số dòng / lỗi", v.rowCount() + " / " + v.errorCount()});
        List<ReconciliationLine> lines = v.reconciliation().stream().map(r -> new ReconciliationLine(r.checkCode(),
                labels.get(r.checkCode()), r.grainKey(), r.controlValue(), r.stagedValue(), r.committedValue(),
                r.tolerance(), r.status(), r.waiverReason())).toList();
        audit.append(AuditWriter.Event.ofCurrentActor("EXPORT", "core", "import_job", jobId, v.legalEntityId(),
                Map.of("file", "reconciliation-report"), true));
        return workbooks.reconciliationReport(header, lines, english);
    }

    /** Deletes staged rows and destroys the job keys 30 days after commit (ADR-0037 §10); run per tenant. */
    @Transactional
    public int purgeDue() {
        jdbc.sql("""
                delete from core.import_row r using core.import_job j
                 where j.tenant_id = :t and j.purge_due_at < now() and r.tenant_id = j.tenant_id
                   and r.import_job_id = j.id""").param("t", tenant()).update();
        return jdbc.sql("""
                update core.import_job set wrapped_dek = null, status = 'PURGED'
                 where tenant_id = :t and purge_due_at < now() and status <> 'PURGED'""")
                .param("t", tenant()).update();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private JobView view(UUID id) {
        List<ReconciliationView> rec = jdbc.sql("""
                select id, check_code, grain_key, control_value, staged_value, committed_value, tolerance, status,
                       waiver_reason
                  from core.import_reconciliation where tenant_id = :t and import_job_id = :id
                 order by check_code, grain_key""")
                .param("t", tenant()).param("id", id)
                .query((rs, n) -> new ReconciliationView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        rs.getString(8), rs.getString(9)))
                .list();
        return jdbc.sql("""
                select id, import_type, owner_module, mode, status, legal_entity_id, as_of_date, file_document_id,
                       file_sha256, row_count, error_count, warning_count, processed_count, failure_reason,
                       depends_on_job_id, created_at, created_by, committed_at, rolled_back_at, signed_off_by,
                       signed_off_at
                  from core.import_job where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id)
                .query((rs, n) -> {
                    Instant committed = instant(rs.getObject(18, OffsetDateTime.class));
                    return new JobView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, LocalDate.class),
                            rs.getObject(8, UUID.class), rs.getString(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
                            rs.getInt(13), rs.getString(14), rs.getObject(15, UUID.class),
                            instant(rs.getObject(16, OffsetDateTime.class)), rs.getObject(17, UUID.class), committed,
                            committed == null ? null : committed.plus(ROLLBACK_WINDOW),
                            instant(rs.getObject(19, OffsetDateTime.class)), rs.getObject(20, UUID.class),
                            instant(rs.getObject(21, OffsetDateTime.class)), rec);
                })
                .optional().orElseThrow(() -> ApiException.notFound("Import job", id));
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    /** The type, if the caller holds its permission. */
    private ImportType runnable(String code) {
        ImportType t = registry.find(code).orElseThrow(() -> ApiException.unprocessable("unknown-import-type",
                "Import type " + code + " is not registered"));
        if (!authorization.hasPermission(t.definition().permission())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "Running " + code + " imports needs " + t.definition().permission());
        }
        return t;
    }

    private void requireLegalEntity(Definition d, Job job) {
        if (job.legalEntityId() != null
                && !authorization.can(d.permission(), new AuthorizationTarget.LegalEntity(job.legalEntityId()))) {
            throw ApiException.notFound("Import job", job.id());
        }
    }

    private boolean mayView(String importType) {
        return authorization.hasTenantWidePermission(CorePermissions.IMPORT_VIEW) || registry.find(importType)
                .map(t -> authorization.hasPermission(t.definition().permission())).orElse(false);
    }

    /** Runs {@code step} on a worker as {@code ctx}, on the IMPORT channel with the job id as correlation id. */
    private void dispatch(TenantContext ctx, UUID jobId, Runnable step) {
        RequestOrigin origin = RequestOrigin.job(RequestOrigin.IMPORT, jobId.toString());
        workers.submit(() -> TenantContext.runWith(ctx, () -> RequestOrigin.runWith(origin, () -> {
            try {
                step.run();
            } catch (RuntimeException ex) {
                log.error("Import job {} step failed", jobId, ex);
            }
        })));
    }

    private static void afterCommit(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            r.run();
                        }
                    });
        } else {
            r.run();
        }
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
