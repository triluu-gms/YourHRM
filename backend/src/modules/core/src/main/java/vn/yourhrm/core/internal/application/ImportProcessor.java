package vn.yourhrm.core.internal.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.CoreEvents.ImportCommitted;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.core.api.ImportType.Column;
import vn.yourhrm.core.api.ImportType.ColumnType;
import vn.yourhrm.core.api.ImportType.CommitResult;
import vn.yourhrm.core.api.ImportType.CommittedRow;
import vn.yourhrm.core.api.ImportType.ControlCheck;
import vn.yourhrm.core.api.ImportType.Definition;
import vn.yourhrm.core.api.ImportType.ImportContext;
import vn.yourhrm.core.api.ImportType.ImportRow;
import vn.yourhrm.core.api.ImportType.ReconciliationValue;
import vn.yourhrm.core.api.ImportType.RowMessage;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.internal.infra.AuditWriter;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.core.internal.infra.ImportFileParser;
import vn.yourhrm.core.internal.infra.ImportWorkbooks;
import vn.yourhrm.core.internal.infra.StagingCipher;
import vn.yourhrm.shared.common.crypto.DataKeys;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The steps of an import job (BR-CORE-110/111/114, ADR-0037), run on a worker with the job's tenant context and the
 * IMPORT request origin bound by {@link ImportService}:
 * <ol>
 *   <li>parse the scanned file into {@code core.import_row} (whole row encrypted with the job's data key, a preview
 *       of the non-sensitive columns in clear), in chunks so that progress is visible;</li>
 *   <li>validate: required values, types, code-or-name lookups, duplicate business keys, then the owner module's
 *       rules; per-row messages in Vietnamese and English (dry-run: nothing is written to the target);</li>
 *   <li>opening balance: compare the control sheet with the staged values of the owner module;</li>
 *   <li>commit: every row in ONE transaction through the owner module's handler (all-or-nothing; rows with errors
 *       are skipped only when the type allows partial commit). Opening-balance loads audit one summary event per
 *       chunk ({@code app.audit_mode = BATCH}, actor IMPORT) instead of one per row.</li>
 * </ol>
 */
@Component
public class ImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImportProcessor.class);
    static final int CHUNK = 1000;
    static final int MAX_ROWS = 200_000;
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ISO_LOCAL_DATE);

    /** The job's columns of the file: key per source column index (null = column not in the template). */
    private record Layout(List<String> headers, List<String> keys) {
    }

    record Job(UUID id, String importType, String mode, String status, UUID legalEntityId, LocalDate asOfDate,
            UUID fileDocumentId, byte[] wrappedDek, int keyVersion, UUID createdBy, boolean xlsx) {
    }

    private final JdbcClient jdbc;
    private final ImportTypeRegistry registry;
    private final ImportLookups lookups;
    private final ImportFileParser parser;
    private final StagingCipher cipher;
    private final ObjectProvider<DataKeys> dataKeys;
    private final DocumentService documents;
    private final CoreJson json;
    private final AuditWriter audit;
    private final Notifications notifications;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final BusinessClock clock;

    public ImportProcessor(JdbcClient jdbc, ImportTypeRegistry registry, ImportLookups lookups, ImportFileParser parser,
            StagingCipher cipher, ObjectProvider<DataKeys> dataKeys, DocumentService documents, CoreJson json,
            AuditWriter audit, Notifications notifications, ApplicationEventPublisher events,
            PlatformTransactionManager txManager, BusinessClock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.registry = registry;
        this.lookups = lookups;
        this.parser = parser;
        this.cipher = cipher;
        this.dataKeys = dataKeys;
        this.documents = documents;
        this.json = json;
        this.audit = audit;
        this.notifications = notifications;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------------------------------- parse + check

    public void parseAndValidate(UUID jobId) {
        try {
            Job job = tx.execute(s -> {
                Job j = job(jobId, true);
                if (!"UPLOADED".equals(j.status())) {
                    return null;
                }
                setStatus(jobId, "PARSING");
                return j;
            });
            if (job == null) {
                return;
            }
            ImportType type = registry.find(job.importType()).orElseThrow();
            byte[] key = parse(job, type);
            validate(tx.execute(s -> job(jobId, false)), type, key);
        } catch (RuntimeException ex) {
            fail(jobId, ex);
        }
    }

    private byte[] parse(Job job, ImportType type) {
        Definition d = type.definition();
        DataKeys keys = dataKeys.getIfAvailable();
        if (keys == null) {
            throw ApiException.conflict("key-service-unavailable", "The key service for staging encryption is not available");
        }
        UUID tenant = TenantContext.current().tenantId();
        DataKeys.DataKey dek = keys.generate(tenant);
        tx.executeWithoutResult(s -> jdbc.sql("update core.import_job set wrapped_dek = :w, key_version = :v where tenant_id = :t and id = :id")
                .param("w", dek.wrapped()).param("v", dek.keyVersion()).param("t", tenant).param("id", job.id()).update());
        byte[] file = tx.execute(s -> documents.content(job.fileDocumentId(), 1));

        // pass 1: the version stamp and the control sheet
        List<String> metaKeys = new ArrayList<>();
        List<Map<String, Object>> control = new ArrayList<>();
        String[] stamp = new String[3];
        if (job.xlsx()) {
            parser.parse(file, true, Set.of(ImportWorkbooks.META, ImportWorkbooks.CONTROL), (sheet, rowNo, cells) -> {
                if (ImportWorkbooks.META.equals(sheet) && rowNo == 1) {
                    for (int i = 0; i < Math.min(3, cells.size()); i++) {
                        stamp[i] = cells.get(i);
                    }
                } else if (ImportWorkbooks.META.equals(sheet) && rowNo == 2) {
                    metaKeys.addAll(cells);
                } else if (ImportWorkbooks.CONTROL.equals(sheet) && rowNo > 1 && cells.size() >= 3
                        && !cells.get(0).isBlank() && !cells.get(2).isBlank()) {
                    control.add(Map.of("check", cells.get(0).strip(),
                            "grain", cells.get(1).isBlank() ? "ALL" : cells.get(1).strip(),
                            "value", decimal(cells.get(2))));
                }
            });
            if (stamp[0] != null) {
                if (!ImportWorkbooks.STAMP.equals(stamp[0]) || !d.code().equals(stamp[1])) {
                    throw ApiException.unprocessable("wrong-template",
                            "The file is a template of " + stamp[1] + ", not of " + d.code());
                }
                if (!d.templateVersion().equals(stamp[2])) {
                    throw ApiException.unprocessable("template-version-mismatch", "The file uses template version "
                            + stamp[2] + "; download the current template (version " + d.templateVersion() + ")");
                }
            }
        }

        // pass 2: the data rows, staged in chunks
        List<Column> columns = ImportWorkbooks.withExternalRef(d.columns());
        Layout[] layout = {null};
        List<Object[]> batch = new ArrayList<>();
        int[] count = {0};
        parser.parse(file, job.xlsx(), Set.of(ImportWorkbooks.DATA), (sheet, rowNo, cells) -> {
            if (layout[0] == null) {
                layout[0] = layout(columns, cells, metaKeys);
                return;
            }
            if (cells.stream().allMatch(String::isBlank)) {
                return;
            }
            if (++count[0] > MAX_ROWS) {
                throw ApiException.unprocessable("too-many-rows", "A file holds at most " + MAX_ROWS + " rows");
            }
            Map<String, String> raw = new LinkedHashMap<>();
            Map<String, String> preview = new LinkedHashMap<>();
            for (int i = 0; i < cells.size() && i < layout[0].keys().size(); i++) {
                String k = layout[0].keys().get(i);
                if (k != null && !cells.get(i).isBlank()) {
                    raw.put(k, cells.get(i).strip());
                }
            }
            for (Column c : columns) {
                if (!c.sensitive() && raw.containsKey(c.key())) {
                    preview.put(c.key(), raw.get(c.key()));
                }
            }
            byte[] payload = json.write(Map.of("cells", cells, "raw", raw)).getBytes(StandardCharsets.UTF_8);
            batch.add(new Object[] {rowNo, cipher.encrypt(dek.plaintext(), job.id(), payload), json.write(preview)});
            if (batch.size() >= CHUNK) {
                flush(job.id(), batch, count[0]);
            }
        });
        if (layout[0] == null) {
            throw ApiException.unprocessable("empty-file", "The file has no header row");
        }
        flush(job.id(), batch, count[0]);
        List<String> missing = columns.stream().filter(Column::required)
                .filter(c -> !layout[0].keys().contains(c.key())).map(Column::key).toList();
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("missing-columns", "Required columns are missing: " + missing);
        }
        tx.executeWithoutResult(s -> jdbc.sql("""
                update core.import_job set status = 'PARSED', row_count = :n, processed_count = :n,
                       source_columns = cast(:cols as jsonb), control_totals = cast(:ctl as jsonb)
                 where tenant_id = :t and id = :id""")
                .param("n", count[0]).param("cols", json.write(layout[0].headers()))
                .param("ctl", control.isEmpty() ? null : json.write(control))
                .param("t", tenant).param("id", job.id()).update());
        return dek.plaintext();
    }

    /** Maps the file's header row to column keys: by key or VI / EN header text, else by the template's stamp. */
    private static Layout layout(List<Column> columns, List<String> headers, List<String> metaKeys) {
        Map<String, String> byText = new HashMap<>();
        for (Column c : columns) {
            byText.put(ImportLookups.searchKey(c.key()), c.key());
            if (c.headerVi() != null) {
                byText.put(ImportLookups.searchKey(c.headerVi()), c.key());
            }
            if (c.headerEn() != null) {
                byText.put(ImportLookups.searchKey(c.headerEn()), c.key());
            }
        }
        List<String> keys = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String text = headers.get(i).replaceAll("\\*\\s*$", "").strip();
            String k = byText.get(ImportLookups.searchKey(text));
            if (k == null && i < metaKeys.size() && byText.containsValue(metaKeys.get(i))) {
                k = metaKeys.get(i);
            }
            keys.add(k != null && used.add(k) ? k : null);
        }
        return new Layout(List.copyOf(headers), keys);
    }

    private void flush(UUID jobId, List<Object[]> batch, int processed) {
        if (batch.isEmpty()) {
            return;
        }
        UUID tenant = TenantContext.current().tenantId();
        List<Object[]> rows = List.copyOf(batch);
        batch.clear();
        tx.executeWithoutResult(s -> {
            for (Object[] r : rows) {
                jdbc.sql("""
                        insert into core.import_row (tenant_id, id, import_job_id, row_no, payload_enc, preview)
                        values (:t, :id, :job, :no, :p, cast(:prev as jsonb))""")
                        .param("t", tenant).param("id", Ids.newId()).param("job", jobId).param("no", r[0])
                        .param("p", r[1]).param("prev", r[2]).update();
            }
            jdbc.sql("update core.import_job set processed_count = :n where tenant_id = :t and id = :id")
                    .param("n", processed).param("t", tenant).param("id", jobId).update();
        });
    }

    private void validate(Job job, ImportType type, byte[] key) {
        UUID tenant = TenantContext.current().tenantId();
        tx.executeWithoutResult(s -> setStatus(job.id(), "VALIDATING"));
        Definition d = type.definition();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> firstRowOfKey = new HashMap<>();
        Map<String, List<ImportLookups.Entry>> lookupCache = new HashMap<>();
        List<ImportRow> valid = new ArrayList<>();
        int[] counts = {0, 0, 0}; // errors, warnings, processed
        int[] after = {0};
        while (true) {
            Integer last = tx.execute(s -> {
                List<Staged> page = page(job.id(), key, after[0], null);
                for (Staged r : page) {
                    List<RowMessage> messages = new ArrayList<>();
                    Map<String, Object> values = convert(d, r.raw(), job, lookupCache, messages);
                    String businessKey = businessKey(d, values);
                    if (messages.stream().noneMatch(m -> "ERROR".equals(m.severity())) && businessKey != null) {
                        Integer first = firstRowOfKey.putIfAbsent(businessKey, r.rowNo());
                        if (first != null) {
                            messages.add(RowMessage.error(String.join("+", d.businessKey()), "duplicate-key",
                                    "Trùng khóa " + businessKey + " với dòng " + first,
                                    "Duplicate key " + businessKey + " (row " + first + ")"));
                        }
                    }
                    ImportRow row = new ImportRow(r.rowNo(), businessKey, r.raw().get(ImportWorkbooks.EXTERNAL_REF),
                            values);
                    if (messages.stream().noneMatch(m -> "ERROR".equals(m.severity()))) {
                        ImportContext ctx = new ImportContext(tenant, job.id(), d.code(), job.mode(),
                                job.legalEntityId(), job.asOfDate(), Set.copyOf(seen));
                        messages.addAll(type.validate(ctx, row));
                    }
                    String status = messages.stream().anyMatch(m -> "ERROR".equals(m.severity())) ? "ERROR"
                            : messages.isEmpty() ? "VALID" : "WARNING";
                    if ("ERROR".equals(status)) {
                        counts[0]++;
                    } else {
                        if ("WARNING".equals(status)) {
                            counts[1]++;
                        }
                        valid.add(row);
                    }
                    if (businessKey != null) {
                        seen.add(businessKey);
                    }
                    counts[2]++;
                    jdbc.sql("""
                            update core.import_row set status = :s, messages = cast(:m as jsonb), business_key = :k
                             where tenant_id = :t and id = :id""")
                            .param("s", status).param("m", messages.isEmpty() ? null : json.write(messages))
                            .param("k", businessKey).param("t", tenant).param("id", r.id()).update();
                }
                jdbc.sql("update core.import_job set processed_count = :n where tenant_id = :t and id = :id")
                        .param("n", counts[2]).param("t", tenant).param("id", job.id()).update();
                return page.isEmpty() ? null : page.getLast().rowNo();
            });
            if (last == null) {
                break;
            }
            after[0] = last;
        }
        boolean openingBalance = "OPENING_BALANCE".equals(job.mode());
        tx.executeWithoutResult(s -> {
            if (openingBalance) {
                reconcile(job, type, valid);
            }
            jdbc.sql("""
                    update core.import_job set status = :s, error_count = :e, warning_count = :w
                     where tenant_id = :t and id = :id""")
                    .param("s", openingBalance ? "RECONCILED" : "VALIDATED").param("e", counts[0])
                    .param("w", counts[1]).param("t", tenant).param("id", job.id()).update();
            notifyCreator(job, openingBalance ? "RECONCILED" : "VALIDATED", counts[0]);
        });
    }

    /** Control sheet vs staged values per check and grain; ROW_COUNT is the engine's own check. */
    private void reconcile(Job job, ImportType type, List<ImportRow> valid) {
        UUID tenant = TenantContext.current().tenantId();
        ImportContext ctx = new ImportContext(tenant, job.id(), job.importType(), job.mode(), job.legalEntityId(),
                job.asOfDate(), Set.of());
        Map<String, BigDecimal> staged = new LinkedHashMap<>();
        staged.put("ROW_COUNT|ALL", BigDecimal.valueOf(valid.size()));
        for (ReconciliationValue v : type.stagedTotals(ctx, valid)) {
            staged.merge(v.checkCode() + "|" + v.grainKey(), v.value(), BigDecimal::add);
        }
        Map<String, BigDecimal> control = new LinkedHashMap<>();
        String totals = jdbc.sql("select control_totals::text from core.import_job where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", job.id()).query(String.class).optional().orElse(null);
        for (Map<String, Object> c : json.listOfMaps(totals)) {
            control.merge(c.get("check") + "|" + c.get("grain"), new BigDecimal(c.get("value").toString()),
                    BigDecimal::add);
        }
        Set<String> declared = new HashSet<>(type.definition().controlChecks().stream().map(ControlCheck::code).toList());
        declared.add("ROW_COUNT");
        Set<String> keys = new java.util.LinkedHashSet<>(control.keySet());
        staged.keySet().stream().filter(k -> !k.startsWith("ROW_COUNT|") || control.containsKey(k)).forEach(keys::add);
        jdbc.sql("delete from core.import_reconciliation where tenant_id = :t and import_job_id = :id")
                .param("t", tenant).param("id", job.id()).update();
        for (String k : keys) {
            String check = k.substring(0, k.indexOf('|'));
            BigDecimal c = control.get(k);
            BigDecimal st = staged.get(k);
            String status = c != null && st != null && c.compareTo(st) == 0 && declared.contains(check)
                    ? "MATCHED" : "MISMATCH";
            jdbc.sql("""
                    insert into core.import_reconciliation (tenant_id, id, import_job_id, check_code, grain_key,
                           control_value, staged_value, status)
                    values (:t, :id, :job, :check, :grain, :c, :s, :status)""")
                    .param("t", tenant).param("id", Ids.newId()).param("job", job.id()).param("check", check)
                    .param("grain", k.substring(k.indexOf('|') + 1)).param("c", c).param("s", st)
                    .param("status", status).update();
        }
    }

    // -------------------------------------------------------------------------------------------------- commit

    /** All-or-nothing commit; the job is COMMITTING when this runs. */
    public void commit(UUID jobId) {
        Job job = tx.execute(s -> job(jobId, false));
        ImportType type = registry.find(job.importType()).orElseThrow();
        Definition d = type.definition();
        UUID tenant = TenantContext.current().tenantId();
        int[] committed = {0};
        int[] failedRow = {0};
        try {
            byte[] key = unwrap(job);
            boolean openingBalance = "OPENING_BALANCE".equals(job.mode());
            tx.executeWithoutResult(s -> {
                if (openingBalance) {
                    jdbc.sql("select set_config('app.audit_mode', 'BATCH', true)").query(String.class).single();
                }
                Map<String, List<ImportLookups.Entry>> lookupCache = new HashMap<>();
                Set<String> seen = new HashSet<>();
                List<CommittedRow> done = new ArrayList<>();
                int after = 0;
                int chunkStart = 0;
                while (true) {
                    List<Staged> page = page(job.id(), key, after, Set.of("VALID", "WARNING"));
                    if (page.isEmpty()) {
                        break;
                    }
                    for (Staged r : page) {
                        failedRow[0] = r.rowNo();
                        List<RowMessage> messages = new ArrayList<>();
                        Map<String, Object> values = convert(d, r.raw(), job, lookupCache, messages);
                        if (messages.stream().anyMatch(m -> "ERROR".equals(m.severity()))) {
                            throw ApiException.conflict("import-data-changed", "Row " + r.rowNo()
                                    + " no longer passes the checks (reference data changed); validate again");
                        }
                        String businessKey = businessKey(d, values);
                        ImportRow row = new ImportRow(r.rowNo(), businessKey, r.raw().get(ImportWorkbooks.EXTERNAL_REF),
                                values);
                        ImportContext ctx = new ImportContext(tenant, job.id(), d.code(), job.mode(),
                                job.legalEntityId(), job.asOfDate(), Set.copyOf(seen));
                        CommitResult result = type.commit(ctx, row);
                        jdbc.sql("""
                                update core.import_row set status = 'COMMITTED', target_entity = :e, target_id = :tid,
                                       commit_action = :a where tenant_id = :t and id = :id""")
                                .param("e", result.targetEntity()).param("tid", result.targetId())
                                .param("a", result.action()).param("t", tenant).param("id", r.id()).update();
                        done.add(new CommittedRow(r.rowNo(), businessKey, values, result.targetEntity(),
                                result.targetId(), result.action()));
                        if (businessKey != null) {
                            seen.add(businessKey);
                        }
                        committed[0]++;
                        if (openingBalance && committed[0] - chunkStart >= CHUNK) {
                            batchAudit(job, chunkStart + 1, committed[0]);
                            chunkStart = committed[0];
                        }
                    }
                    after = page.getLast().rowNo();
                    jdbc.sql("update core.import_job set processed_count = :n where tenant_id = :t and id = :id")
                            .param("n", committed[0]).param("t", tenant).param("id", job.id()).update();
                }
                failedRow[0] = 0;
                if (openingBalance && committed[0] > chunkStart) {
                    batchAudit(job, chunkStart + 1, committed[0]);
                }
                jdbc.sql("update core.import_row set status = 'SKIPPED' where tenant_id = :t and import_job_id = :id and status = 'ERROR'")
                        .param("t", tenant).param("id", job.id()).update();
                if (openingBalance) {
                    ImportContext ctx = new ImportContext(tenant, job.id(), d.code(), job.mode(), job.legalEntityId(),
                            job.asOfDate(), Set.of());
                    recordCommitted(job, type.committedTotals(ctx, done), done.size());
                }
                jdbc.sql("""
                        update core.import_job set status = 'COMMITTED', committed_at = now(),
                               purge_due_at = now() + interval '30 days', processed_count = :n
                         where tenant_id = :t and id = :id""")
                        .param("n", committed[0]).param("t", tenant).param("id", job.id()).update();
                audit.append(AuditWriter.Event.ofCurrentActor("IMPORT_COMMIT", d.ownerModule(), "import_job", job.id(),
                        job.legalEntityId(), Map.of("importType", d.code(), "mode", job.mode(),
                                "rows", String.valueOf(committed[0]),
                                "workflowBypassed", String.valueOf(openingBalance)), false));
                notifyCreator(job, "COMMITTED", 0);
            });
            events.publishEvent(new ImportCommitted(tenant, job.id(), d.code(), d.ownerModule(), job.mode(),
                    "OPENING_BALANCE".equals(job.mode()), committed[0]));
        } catch (RuntimeException ex) {
            fail(jobId, failedRow[0] > 0 ? new IllegalStateException("Row " + failedRow[0] + ": " + reason(ex), ex) : ex);
        }
    }

    /** One summary event per committed chunk of an opening-balance load (ADR-0027 §2, ADR-0037 §8). */
    private void batchAudit(Job job, int fromRow, int toRow) {
        TenantContext ctx = TenantContext.current();
        audit.append(new AuditWriter.Event(ctx.tenantId(), "IMPORT", ctx.userAccountId(), null, null, "IMPORT_CHUNK",
                registry.find(job.importType()).orElseThrow().definition().ownerModule(), "import_job", job.id(),
                job.legalEntityId(), Map.of("importType", job.importType(), "fromRow", String.valueOf(fromRow),
                        "toRow", String.valueOf(toRow), "workflowBypassed", "true"), false));
    }

    private void recordCommitted(Job job, List<ReconciliationValue> values, int rows) {
        UUID tenant = TenantContext.current().tenantId();
        Map<String, BigDecimal> committed = new LinkedHashMap<>();
        committed.put("ROW_COUNT|ALL", BigDecimal.valueOf(rows));
        for (ReconciliationValue v : values) {
            committed.merge(v.checkCode() + "|" + v.grainKey(), v.value(), BigDecimal::add);
        }
        committed.forEach((k, v) -> jdbc.sql("""
                update core.import_reconciliation set committed_value = :v
                 where tenant_id = :t and import_job_id = :job and check_code = :c and grain_key = :g""")
                .param("v", v).param("t", tenant).param("job", job.id())
                .param("c", k.substring(0, k.indexOf('|'))).param("g", k.substring(k.indexOf('|') + 1)).update());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A staged row with its decrypted source cells and raw texts by column key. */
    record Staged(UUID id, int rowNo, List<String> cells, Map<String, String> raw, String status, String messages,
            String businessKey, String targetEntity, UUID targetId, String action) {
    }

    /** Up to {@link #CHUNK} rows after {@code afterRowNo}, decrypted. */
    List<Staged> page(UUID jobId, byte[] key, int afterRowNo, Set<String> statuses) {
        return jdbc.sql("""
                select id, row_no, payload_enc, status, messages::text, business_key, target_entity, target_id,
                       commit_action
                  from core.import_row
                 where tenant_id = :t and import_job_id = :job and row_no > :after
                   and (cast(:st as text[]) is null or status = any(cast(:st as text[])))
                 order by row_no limit :lim""")
                .param("t", TenantContext.current().tenantId()).param("job", jobId).param("after", afterRowNo)
                .param("st", statuses == null ? null : statuses.toArray(String[]::new)).param("lim", CHUNK)
                .query((rs, n) -> {
                    Map<String, Object> payload = json.map(new String(cipher.decrypt(key, jobId, rs.getBytes(3)),
                            StandardCharsets.UTF_8));
                    @SuppressWarnings("unchecked")
                    List<String> cells = (List<String>) payload.get("cells");
                    @SuppressWarnings("unchecked")
                    Map<String, String> raw = (Map<String, String>) payload.get("raw");
                    return new Staged(rs.getObject(1, UUID.class), rs.getInt(2), cells, raw, rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class),
                            rs.getString(9));
                })
                .list();
    }

    byte[] unwrap(Job job) {
        DataKeys keys = dataKeys.getIfAvailable();
        if (keys == null || job.wrappedDek() == null) {
            throw ApiException.conflict("staging-unavailable", "The job's staged rows can no longer be read");
        }
        return keys.unwrap(TenantContext.current().tenantId(), job.wrappedDek(), job.keyVersion());
    }

    /** Typed values of a row; generic problems go to {@code messages}. */
    private Map<String, Object> convert(Definition d, Map<String, String> raw, Job job,
            Map<String, List<ImportLookups.Entry>> lookupCache, List<RowMessage> messages) {
        Map<String, Object> values = new HashMap<>();
        LocalDate asOf = job.asOfDate() != null ? job.asOfDate() : clock.today();
        for (Column c : ImportWorkbooks.withExternalRef(d.columns())) {
            String v = raw.get(c.key());
            String label = c.headerVi() == null ? c.key() : c.headerVi();
            String labelEn = c.headerEn() == null ? c.key() : c.headerEn();
            if (v == null || v.isBlank()) {
                if (c.required()) {
                    messages.add(RowMessage.error(c.key(), "required", label + ": bắt buộc nhập",
                            labelEn + ": required"));
                }
                continue;
            }
            try {
                values.put(c.key(), switch (c.type()) {
                    case TEXT -> v;
                    case INTEGER -> Long.parseLong(v.replace(" ", ""));
                    case DECIMAL -> decimal(v);
                    case DATE -> date(v);
                    case BOOLEAN -> bool(v);
                    case CODE -> {
                        List<ImportLookups.Entry> entries = lookupCache.computeIfAbsent(c.lookup(),
                                l -> lookups.entries(l, asOf));
                        ImportLookups.Match m = ImportLookups.match(entries, v);
                        if (!m.found()) {
                            messages.add(m.ambiguous().isEmpty()
                                    ? RowMessage.error(c.key(), "not-found", label + ": không tìm thấy '" + v + "'",
                                            labelEn + ": '" + v + "' not found")
                                    : RowMessage.error(c.key(), "ambiguous", label + ": tên '" + v
                                            + "' trùng nhiều mã " + m.ambiguous() + ", hãy nhập mã",
                                            labelEn + ": name '" + v + "' matches " + m.ambiguous() + "; enter the code"));
                        }
                        yield m.value();
                    }
                });
            } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException ex) {
                String type = c.type().name().toLowerCase(Locale.ROOT);
                messages.add(RowMessage.error(c.key(), "invalid-" + type, label + ": giá trị '" + v
                        + "' không hợp lệ (" + type + ")", labelEn + ": invalid " + type + " '" + v + "'"));
            }
        }
        values.values().removeIf(java.util.Objects::isNull);
        return values;
    }

    private static String businessKey(Definition d, Map<String, Object> values) {
        List<String> parts = new ArrayList<>();
        for (String k : d.businessKey()) {
            Object v = values.get(k);
            if (v == null) {
                return null;
            }
            parts.add(v.toString().toUpperCase(Locale.ROOT));
        }
        return String.join("|", parts);
    }

    static BigDecimal decimal(String v) {
        String s = v.replace(" ", "");
        if (s.contains(",")) {
            throw new NumberFormatException("Use '.' as the decimal point");
        }
        return new BigDecimal(s);
    }

    private static LocalDate date(String v) {
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(v.strip(), f);
            } catch (DateTimeParseException ignored) {
                // next pattern
            }
        }
        throw new DateTimeParseException("Not a date", v, 0);
    }

    private static Boolean bool(String v) {
        return switch (ImportLookups.searchKey(v)) {
            case "co", "yes", "true", "1", "x", "y" -> true;
            case "khong", "no", "false", "0", "n" -> false;
            default -> throw new IllegalArgumentException("Not a yes / no value");
        };
    }

    Job job(UUID id, boolean forUpdate) {
        return jdbc.sql("""
                select j.id, j.import_type, j.mode, j.status, j.legal_entity_id, j.as_of_date, j.file_document_id,
                       j.wrapped_dek, j.key_version, j.created_by, v.content_type
                  from core.import_job j
                  left join core.document_version v on v.tenant_id = j.tenant_id and v.document_id = j.file_document_id
                       and v.version_no = 1
                 where j.tenant_id = :t and j.id = :id""" + (forUpdate ? " for update of j" : ""))
                .param("t", TenantContext.current().tenantId()).param("id", id)
                .query((rs, n) -> new Job(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, UUID.class), rs.getBytes(8), rs.getInt(9), rs.getObject(10, UUID.class),
                        DocumentService.XLSX.equals(rs.getString(11))))
                .optional().orElseThrow(() -> ApiException.notFound("Import job", id));
    }

    void setStatus(UUID jobId, String status) {
        jdbc.sql("update core.import_job set status = :s, processed_count = 0 where tenant_id = :t and id = :id")
                .param("s", status).param("t", TenantContext.current().tenantId()).param("id", jobId).update();
    }

    void fail(UUID jobId, RuntimeException ex) {
        log.warn("Import job {} failed: {}", jobId, reason(ex));
        tx.executeWithoutResult(s -> {
            jdbc.sql("update core.import_job set status = 'FAILED', failure_reason = :r where tenant_id = :t and id = :id")
                    .param("r", reason(ex)).param("t", TenantContext.current().tenantId()).param("id", jobId).update();
            Job job = job(jobId, false);
            notifyCreator(job, "FAILED", 0);
        });
    }

    private static String reason(RuntimeException ex) {
        String m = ex instanceof ApiException a ? a.getMessage() : ex.getMessage();
        if (m == null) {
            m = ex.getClass().getSimpleName();
        }
        return m.length() > 1000 ? m.substring(0, 1000) : m;
    }

    private void notifyCreator(Job job, String status, int errors) {
        if (job.createdBy() == null) {
            return;
        }
        notifications.send(new Notifications.SendRequest(CoreNotificationTemplates.IMPORT_FINISHED, "core",
                Notifications.Recipients.users(job.createdBy()), Map.of("importType", job.importType(),
                        "status", status, "errorCount", errors), "import_job", job.id(), "/imports/" + job.id(),
                "import:" + job.id() + ":" + status, null));
    }
}
