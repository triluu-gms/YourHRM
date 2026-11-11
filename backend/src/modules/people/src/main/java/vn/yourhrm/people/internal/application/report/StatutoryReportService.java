package vn.yourhrm.people.internal.application.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.DocumentGeneration;
import vn.yourhrm.core.api.DocumentGeneration.Generated;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.core.api.Documents.DocumentVersionView;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.report.LabourBookService.Book;
import vn.yourhrm.people.internal.application.report.LabourReportService.Fluctuation;
import vn.yourhrm.people.internal.application.report.LabourReportService.LabourChange;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrJson;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The statutory report register {@code hr.statutory_report} (US-PPL-071, BR-PPL-140..142): one live report per legal
 * entity × type × period; generating stores the file in Core's document service and regenerating adds a new version
 * of the same document (all versions are kept); HR marks the report submitted with the date, channel and reference
 * and uploads the authority's receipt (ACKNOWLEDGED, {@code receipt_document_id}). HR's review notes are stored in
 * {@code hr_notes} and printed again when the report is regenerated without new notes. Types: the labour-book export, the 6-monthly labour change
 * report and the monthly labour fluctuation notice (FOREIGN_WORKER / INITIAL_DECLARATION are Phase 2 / 3).
 */
@Service
public class StatutoryReportService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Set<String> TYPES = Set.of(ReportKeys.LABOUR_BOOK_EXPORT, ReportKeys.LABOUR_CHANGE_6M,
            ReportKeys.LABOUR_FLUCTUATION_MONTHLY);
    static final Set<String> CHANNELS = Set.of("PORTAL", "PAPER", "EMAIL", "OTHER");

    /**
     * What to generate. LABOUR_CHANGE_6M: {@code year} + {@code period} (e.g. JUNE / DECEMBER);
     * LABOUR_FLUCTUATION_MONTHLY: {@code year} + {@code month}; LABOUR_BOOK_EXPORT: {@code asOf} (default today).
     * {@code notes}: HR's notes per report line (6-monthly), printed in the file and stored on the report; null keeps
     * (and re-prints) the stored notes, an empty map clears them.
     */
    public record GenerateRequest(UUID legalEntityId, String reportType, Integer year, String period, Integer month,
            LocalDate asOf, Map<String, String> notes) {
    }

    /**
     * {@code hrNotes}: HR's review notes per report line ({@code hr_notes}); {@code receiptDocumentId}: the authority's
     * receipt ({@code receipt_document_id}); {@code receiptDocumentIds}: the same as a list (kept for API
     * compatibility).
     */
    public record ReportView(UUID id, UUID legalEntityId, String reportType, LocalDate periodStart, LocalDate periodEnd,
            LocalDate dueDate, String templateVersion, UUID generatedDocumentId, Instant generatedAt, UUID generatedBy,
            Integer rowCount, Instant submittedAt, UUID submittedBy, String submissionChannel, String submissionRef,
            String status, int version, Map<String, String> hrNotes, UUID receiptDocumentId,
            List<DocumentVersionView> versions, List<UUID> receiptDocumentIds) {
    }

    /** A generated report with the content behind the file (for on-screen review). */
    public record Generation(ReportView report, Object content) {
    }

    private record Built(LocalDate start, LocalDate end, LocalDate due, String templateVersion, String fileName,
            byte[] content, int rows, Object data) {
    }

    private final HrDb db;
    private final HrJson json;
    private final LabourBookService book;
    private final LabourReportService reports;
    private final Documents documents;
    private final DocumentGeneration generation;
    private final AuditLog audit;
    private final BusinessClock clock;

    public StatutoryReportService(HrDb db, HrJson json, LabourBookService book, LabourReportService reports,
            Documents documents, DocumentGeneration generation, AuditLog audit, BusinessClock clock) {
        this.db = db;
        this.json = json;
        this.book = book;
        this.reports = reports;
        this.documents = documents;
        this.generation = generation;
        this.audit = audit;
        this.clock = clock;
    }

    @HrTransactional(readOnly = true)
    public List<ReportView> list(UUID legalEntityId, String type, Integer year) {
        reports.requireLegalEntity(PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE,
                legalEntityId);
        return db.jdbc().sql("""
                        select * from hr.statutory_report
                         where tenant_id = :t and legal_entity_id = :le
                           and (cast(:type as text) is null or report_type = :type)
                           and (cast(:y as int) is null or extract(year from period_start) = :y)
                         order by period_start desc, report_type, created_at desc""")
                .param("t", tenant()).param("le", legalEntityId).param("type", type).param("y", year)
                .query(this::map).list();
    }

    @HrTransactional(readOnly = true)
    public ReportView get(UUID id) {
        ReportView r = require(id);
        reports.requireLegalEntity(PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE,
                r.legalEntityId());
        return withDocuments(r);
    }

    /** Generates (or regenerates as a new file version) the live report of the legal entity, type and period. */
    @HrTransactional
    public Generation generate(GenerateRequest req) {
        if (req.reportType() == null || !TYPES.contains(req.reportType())) {
            throw ApiException.unprocessable("report-type-not-supported", "Report type " + req.reportType()
                    + " is not generated in this phase; supported: " + TYPES);
        }
        reports.requireLegalEntity(PeoplePermissions.STATUTORY_REPORT_EXECUTE, null, req.legalEntityId());
        Built built = build(req);
        UUID t = tenant();
        Optional<ReportView> live = db.jdbc().sql("""
                        select * from hr.statutory_report
                         where tenant_id = :t and legal_entity_id = :le and report_type = :type and period_start = :s
                           and status <> 'CANCELLED' for update""")
                .param("t", t).param("le", req.legalEntityId()).param("type", req.reportType())
                .param("s", built.start())
                .query(this::map).optional();
        if (live.isPresent() && List.of("SUBMITTED", "ACKNOWLEDGED").contains(live.get().status())) {
            throw ApiException.unprocessable("report-already-submitted", "This report was submitted on "
                    + live.get().submittedAt() + "; cancel it before generating a corrected one");
        }
        // no new notes: the stored review notes are printed again
        Map<String, String> notes = req.notes() != null ? req.notes()
                : live.map(ReportView::hrNotes).orElse(Map.of());
        Built b = req.notes() == null && !notes.isEmpty()
                ? build(new GenerateRequest(req.legalEntityId(), req.reportType(), req.year(), req.period(),
                        req.month(), req.asOf(), notes))
                : built;
        UUID id = live.map(ReportView::id).orElseGet(() -> {
            UUID nid = Ids.newId();
            db.jdbc().sql("""
                            insert into hr.statutory_report (tenant_id, id, legal_entity_id, report_type, period_start,
                                   period_end, due_date, status, created_by, updated_by)
                            values (:t, :id, :le, :type, :s, :e, :due, 'DRAFT', :a, :a)""")
                    .param("t", t).param("id", nid).param("le", req.legalEntityId()).param("type", req.reportType())
                    .param("s", b.start()).param("e", b.end()).param("due", b.due()).param("a", actor()).update();
            return nid;
        });
        UUID existingDoc = live.map(ReportView::generatedDocumentId).orElse(null);
        Documents.DocumentView doc = existingDoc == null
                ? documents.upload(new Documents.Upload(ReportKeys.REPORT_DOC, id, req.legalEntityId(), null,
                        title(req.reportType(), b), "RESTRICTED", b.fileName(), ExcelSheet.MEDIA_TYPE, b.content()))
                : documents.addVersion(existingDoc, b.fileName(), ExcelSheet.MEDIA_TYPE, b.content());
        db.jdbc().sql("""
                        update hr.statutory_report set period_end = :e, due_date = :due, template_version = :tv,
                               generated_document_id = :doc, generated_at = now(), generated_by = :a, row_count = :rows,
                               hr_notes = :notes, status = 'GENERATED', version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("e", b.end()).param("due", b.due()).param("tv", b.templateVersion()).param("doc", doc.id())
                .param("a", actor()).param("rows", b.rows()).param("notes", notes.isEmpty() ? null : json.write(notes))
                .param("t", t).param("id", id).update();
        Map<String, String> details = new HashMap<>();
        details.put("reportType", req.reportType());
        details.put("periodStart", b.start().toString());
        details.put("fileVersion", String.valueOf(doc.currentVersionNo()));
        if (!notes.isEmpty()) {
            details.put("notes", notes.toString());
        }
        audit.record(new BusinessAction("GENERATE_STATUTORY_REPORT", "hr", "statutory_report", id,
                req.legalEntityId(), details));
        return new Generation(withDocuments(require(id)), b.data());
    }

    /** Marks a generated report submitted to the authority (date, channel, reference). */
    @HrTransactional
    public ReportView submit(UUID id, int expectedVersion, LocalDate submittedOn, String channel, String reference) {
        ReportView r = lockForExecute(id);
        if (!"GENERATED".equals(r.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Only a generated report can be submitted; "
                    + "it is " + r.status());
        }
        requireVersion(r, expectedVersion);
        if (submittedOn == null || submittedOn.isAfter(clock.today())) {
            throw ApiException.unprocessable("invalid-submission-date", "The submission date is required and cannot "
                    + "be in the future");
        }
        if (channel != null && !CHANNELS.contains(channel)) {
            throw ApiException.unprocessable("invalid-submission-channel", "Channel must be one of " + CHANNELS);
        }
        db.jdbc().sql("""
                        update hr.statutory_report set status = 'SUBMITTED', submitted_at = :at, submitted_by = :a,
                               submission_channel = :ch, submission_ref = :ref, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("at", Timestamp.from(submittedOn.atStartOfDay(BUSINESS_ZONE).toInstant())).param("a", actor())
                .param("ch", channel).param("ref", reference).param("t", tenant()).param("id", id).update();
        audit.record(new BusinessAction("SUBMIT_STATUTORY_REPORT", "hr", "statutory_report", id, r.legalEntityId(),
                Map.of("submittedOn", submittedOn.toString(), "channel", String.valueOf(channel))));
        return withDocuments(require(id));
    }

    /** Uploads the authority's receipt of a submitted report (SUBMITTED → ACKNOWLEDGED). */
    @HrTransactional
    public ReportView uploadReceipt(UUID id, String title, String fileName, String mediaType, byte[] content) {
        ReportView r = lockForExecute(id);
        if (!List.of("SUBMITTED", "ACKNOWLEDGED").contains(r.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Mark the report submitted before "
                    + "uploading the receipt; it is " + r.status());
        }
        Documents.DocumentView doc = documents.upload(new Documents.Upload(ReportKeys.RECEIPT_DOC, id,
                r.legalEntityId(), null, title == null ? "Biên nhận " + r.reportType() + " " + r.periodStart() : title,
                "CONFIDENTIAL", fileName, mediaType, content));
        db.jdbc().sql("""
                        update hr.statutory_report set status = 'ACKNOWLEDGED', receipt_document_id = :doc,
                               version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("doc", doc.id()).param("a", actor()).param("t", tenant()).param("id", id).update();
        audit.record(new BusinessAction("UPLOAD_STATUTORY_REPORT_RECEIPT", "hr", "statutory_report", id,
                r.legalEntityId(), Map.of("documentId", doc.id().toString())));
        return withDocuments(require(id));
    }

    /** Cancels a report (keeps its files); a new live report of the same period can then be generated. */
    @HrTransactional
    public ReportView cancel(UUID id, int expectedVersion) {
        ReportView r = lockForExecute(id);
        requireVersion(r, expectedVersion);
        if ("CANCELLED".equals(r.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "The report is already cancelled");
        }
        db.jdbc().sql("""
                        update hr.statutory_report set status = 'CANCELLED', version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("a", actor()).param("t", tenant()).param("id", id).update();
        audit.record(new BusinessAction("CANCEL_STATUTORY_REPORT", "hr", "statutory_report", id, r.legalEntityId(),
                Map.of("previousStatus", r.status())));
        return withDocuments(require(id));
    }

    /**
     * The PDF of a 6-monthly report from the tenant's Core template {@code HR_LABOUR_CHANGE_6M}; without notes the
     * stored review notes are printed.
     */
    @HrTransactional
    public Generated pdf(UUID id, Map<String, String> notes) {
        ReportView r = lockForExecute(id);
        if (!ReportKeys.LABOUR_CHANGE_6M.equals(r.reportType()) || "CANCELLED".equals(r.status())) {
            throw ApiException.unprocessable("pdf-not-available", "A PDF layout exists for live 6-monthly labour "
                    + "change reports only (the others are exported in their spreadsheet layout)");
        }
        Object[] period = periodOf(r.legalEntityId(), r.periodStart());
        int year = (Integer) period[0];
        String code = (String) period[1];
        LabourChange c = reports.labourChange(r.legalEntityId(), year, code,
                notes == null || notes.isEmpty() ? r.hrNotes() : notes);
        return generation.generate(new DocumentGeneration.GenerateRequest(ReportKeys.TEMPLATE_LABOUR_CHANGE_6M,
                r.legalEntityId(), id, null, title(r.reportType(), null) + " " + code + "/" + year, clock.today(),
                reports.labourChangeFields(c)));
    }

    @HrTransactional(readOnly = true)
    public Documents.DownloadLink download(UUID id, Integer fileVersion) {
        ReportView r = get(id);
        if (r.generatedDocumentId() == null) {
            throw ApiException.notFound("Report file", id);
        }
        return documents.downloadLink(r.generatedDocumentId(), fileVersion);
    }

    // ---------------------------------------------------------------------------------------------------------

    private Built build(GenerateRequest req) {
        UUID le = req.legalEntityId();
        return switch (req.reportType()) {
            case ReportKeys.LABOUR_BOOK_EXPORT -> {
                LocalDate asOf = req.asOf() == null ? clock.today() : req.asOf();
                Book bk = book.build(le, asOf, PeoplePermissions.STATUTORY_REPORT_EXECUTE, true, null);
                yield new Built(asOf, asOf, null, "DECREE_145_2020_ART3", "so-quan-ly-lao-dong_" + asOf + ".xlsx",
                        book.excel(bk), bk.entries().size(), bk);
            }
            case ReportKeys.LABOUR_CHANGE_6M -> {
                int year = requireYear(req);
                LabourChange c = reports.labourChange(le, year, req.period(), req.notes());
                yield new Built(c.periodStart(), c.periodEnd(), c.dueDate(), "FORM " + c.formCode(),
                        "bao-cao-thay-doi-lao-dong_" + year + "-" + c.periodCode() + ".xlsx",
                        reports.labourChangeExcel(c), c.headcountEnd(), c);
            }
            default -> {
                int year = requireYear(req);
                if (req.month() == null || req.month() < 1 || req.month() > 12) {
                    throw ApiException.unprocessable("invalid-report-period", "Give the month (1-12) of the notice");
                }
                YearMonth ym = YearMonth.of(year, req.month());
                Fluctuation f = reports.fluctuation(le, ym, true);
                yield new Built(f.periodStart(), f.periodEnd(), f.dueDate(), "FORM " + f.formCode(),
                        "thong-bao-bien-dong-lao-dong_" + ym + ".xlsx", reports.fluctuationExcel(f), f.rows().size(), f);
            }
        };
    }

    /** The (year, period code) of the 6-monthly pack whose period starts on {@code start}. */
    private Object[] periodOf(UUID le, LocalDate start) {
        for (int y : new int[] {start.getYear(), start.getYear() + 1, start.getYear() - 1}) {
            for (String code : reports.sixMonthCodes(le, y)) {
                if (reports.sixMonthPeriod(le, y, code).start().equals(start)) {
                    return new Object[] {y, code};
                }
            }
        }
        throw ApiException.unprocessable("invalid-report-period", "The period of this report no longer matches the "
                + "legal parameter " + ReportKeys.CHANGE_6M_PERIODS);
    }

    private static int requireYear(GenerateRequest req) {
        if (req.year() == null || req.year() < 2000 || req.year() > 2100) {
            throw ApiException.unprocessable("invalid-report-period", "Give the report year");
        }
        return req.year();
    }

    private static String title(String type, Built b) {
        String name = switch (type) {
            case ReportKeys.LABOUR_BOOK_EXPORT -> "Sổ quản lý lao động";
            case ReportKeys.LABOUR_CHANGE_6M -> "Báo cáo tình hình thay đổi về lao động";
            default -> "Thông báo biến động lao động";
        };
        return b == null ? name : name + " " + ExcelSheet.date(b.start()) + " - " + ExcelSheet.date(b.end());
    }

    private ReportView lockForExecute(UUID id) {
        db.jdbc().sql("select id from hr.statutory_report where tenant_id = :t and id = :id for update")
                .param("t", tenant()).param("id", id).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Statutory report", id));
        ReportView r = require(id);
        try {
            reports.requireLegalEntity(PeoplePermissions.STATUTORY_REPORT_EXECUTE, null, r.legalEntityId());
        } catch (ApiException e) {
            throw ApiException.notFound("Statutory report", id);
        }
        return r;
    }

    private ReportView require(UUID id) {
        return db.jdbc().sql("select * from hr.statutory_report where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", id).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("Statutory report", id));
    }

    private ReportView withDocuments(ReportView r) {
        List<DocumentVersionView> versions = r.generatedDocumentId() == null ? List.of()
                : documents.get(r.generatedDocumentId()).versions();
        return new ReportView(r.id(), r.legalEntityId(), r.reportType(), r.periodStart(), r.periodEnd(), r.dueDate(),
                r.templateVersion(), r.generatedDocumentId(), r.generatedAt(), r.generatedBy(), r.rowCount(),
                r.submittedAt(), r.submittedBy(), r.submissionChannel(), r.submissionRef(), r.status(), r.version(),
                r.hrNotes(), r.receiptDocumentId(), versions, r.receiptDocumentIds());
    }

    private static void requireVersion(ReportView r, int expected) {
        if (r.version() != expected) {
            throw ApiException.conflict("stale-version", "Report was changed (version " + r.version() + ", expected "
                    + expected + "); reload and retry");
        }
    }

    private ReportView map(ResultSet rs, int n) throws SQLException {
        Timestamp gen = rs.getTimestamp("generated_at");
        Timestamp sub = rs.getTimestamp("submitted_at");
        Map<String, String> notes = new LinkedHashMap<>();
        json.map(rs.getString("hr_notes")).forEach((k, v) -> notes.put(k, v == null ? "" : String.valueOf(v)));
        UUID receipt = rs.getObject("receipt_document_id", UUID.class);
        return new ReportView(rs.getObject("id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                rs.getString("report_type"), rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getString("template_version"), rs.getObject("generated_document_id", UUID.class),
                gen == null ? null : gen.toInstant(), rs.getObject("generated_by", UUID.class),
                rs.getObject("row_count", Integer.class), sub == null ? null : sub.toInstant(),
                rs.getObject("submitted_by", UUID.class), rs.getString("submission_channel"),
                rs.getString("submission_ref"), rs.getString("status"), rs.getInt("version"), Map.copyOf(notes),
                receipt, List.of(), receipt == null ? List.of() : List.of(receipt));
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }
}
