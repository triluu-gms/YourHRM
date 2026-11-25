package vn.yourhrm.attendance.internal.application.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePeriodClosed;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.AttendanceResults.Result;
import vn.yourhrm.attendance.internal.domain.period.ResultLines;
import vn.yourhrm.attendance.internal.infra.AttOutbox;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore.Period;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.NewLine;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.NewResult;
import vn.yourhrm.attendance.internal.infra.period.ResultImportStore;
import vn.yourhrm.attendance.internal.infra.period.ResultImportStore.PeriodRef;
import vn.yourhrm.attendance.internal.infra.period.ResultImportStore.StagedLine;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Attendance Result import (BR-ATT-025 / 076, R2-18, GAP-060, US-ATT-019): the quantities of a period per employee and
 * category, loaded from Excel by a tenant that has Payroll but does not use T&amp;A's processing, become a standard
 * result version that Payroll reads and holds exactly like a calculated one (same categories as BRD §7.3, same
 * versioning, same close-hold protocol).
 * <ul>
 *   <li><b>Rows</b> ({@link #stage}): the employee's pay group on the period start gives the attendance period of the
 *       job's legal entity, which must be OPEN or IN_REVIEW (reopen a CLOSED one first, BR-ATT-073). The line covers
 *       the employment's days inside the period, on its assignment line of the first day. Each committed row is staged
 *       in {@code att.attendance_result_import_line}, in its own T&amp;A transaction (Core's commit cannot carry the
 *       {@code att} writes, ADR-0021).</li>
 *   <li><b>Publish</b> ({@link #publish}): after Core committed the job, every period of the job gets ONE new
 *       PERIOD-scope version of source IMPORT ({@code close_cause} IMPORT) with exactly the imported lines, in a single
 *       T&amp;A transaction (header + lines + seal); the previous version is withdrawn, the period becomes CLOSED and
 *       {@code att.AttendancePeriodClosed} is published. All periods of a job publish together or not at all; a failed
 *       publish keeps the rows STAGED and can be repeated ({@code POST /result-imports/{jobId}/publish}).</li>
 * </ul>
 */
@Service
public class ResultImportService {

    static final String RULE_VERSION = "IMPORT/" + ResultLines.RULE_VERSION;
    private static final Pattern CATEGORY = Pattern.compile("^[A-Z][A-Z_]*(\\.[A-Za-z0-9_-]+)*$");
    private static final Set<String> UNITS = Set.of("DAY", "HOUR", "MINUTE", "COUNT");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("100000");

    /** A row to stage. */
    public record Row(UUID importJobId, int rowNo, UUID legalEntityId, UUID employmentId, LocalDate periodStart,
            String category, String unit, BigDecimal quantity, BigDecimal periodQuantity) {
    }

    public record PublishedPeriod(UUID periodId, LocalDate periodStart, UUID resultId, int version, int lines) {
    }

    /** @param status STAGED (waiting for publish), PUBLISHED, or EMPTY (nothing staged for the job) */
    public record JobStatus(UUID importJobId, String status, int stagedLines, int publishedLines,
            List<PublishedPeriod> periods) {
    }

    private final ResultImportStore store;
    private final AttendancePeriodStore periods;
    private final AttendanceResultStore results;
    private final PunchStore employments;
    private final TimesheetStore timesheets;
    private final AttOutbox outbox;
    private final AuthorizationService authz;
    private final AuditLog audit;

    public ResultImportService(ResultImportStore store, AttendancePeriodStore periods, AttendanceResultStore results,
            PunchStore employments, TimesheetStore timesheets, AttOutbox outbox, AuthorizationService authz,
            AuditLog audit) {
        this.store = store;
        this.periods = periods;
        this.results = results;
        this.employments = employments;
        this.timesheets = timesheets;
        this.outbox = outbox;
        this.authz = authz;
        this.audit = audit;
    }

    // ------------------------------------------------------------------------------------------------ rows

    /** Validates and stages one row (inside the caller's T&amp;A transaction); returns the staged row id. */
    @AttTransactional
    public UUID stage(Row r) {
        if (r.periodStart() == null) {
            throw invalid("period-start-required", "The period start is required");
        }
        var emp = employments.employment(r.employmentId())
                .orElseThrow(() -> invalid("employment-not-in-attendance",
                        "The employee is not known to Time & Attendance yet (load the employee master first)"));
        UUID payGroup = timesheets.payGroup(r.employmentId(), r.periodStart())
                .orElseThrow(() -> invalid("no-pay-group", "The employee has no pay group on " + r.periodStart()
                        + " (Payroll's pay-group assignment is needed to find the attendance period)"));
        PeriodRef period = store.period(r.legalEntityId(), payGroup, r.periodStart())
                .orElseThrow(() -> invalid("period-not-found", "No attendance period of the legal entity and the"
                        + " employee's pay group starts on " + r.periodStart() + " (the pay calendar creates them)"));
        if (!"OPEN".equals(period.status()) && !"IN_REVIEW".equals(period.status())) {
            throw ApiException.conflict("period-not-open", "The attendance period starting " + r.periodStart()
                    + " is " + period.status() + "; reopen it before importing its result again (BR-ATT-073)");
        }
        LocalDate from = emp.hireDate() != null && emp.hireDate().isAfter(period.periodStart()) ? emp.hireDate()
                : period.periodStart();
        LocalDate to = emp.terminationDate() != null && emp.terminationDate().isBefore(period.periodEnd())
                ? emp.terminationDate() : period.periodEnd();
        if ("CANCELLED".equals(emp.status()) || to.isBefore(from)) {
            throw invalid("not-employed-in-period", "The employee is not employed in the period " + period.periodStart()
                    + " – " + period.periodEnd());
        }
        var line = store.line(r.employmentId(), from).orElse(null);
        if (line != null && !r.legalEntityId().equals(line.legalEntityId())) {
            throw invalid("other-legal-entity", "On " + from + " the employee works for another legal entity");
        }
        if (!authz.can(AttendancePermissions.RESULT_IMPORT, new AuthorizationTarget.LegalEntity(r.legalEntityId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "legal-entity-not-in-scope",
                    "The legal entity is outside your data scope");
        }
        String category = category(r.category());
        String unit = unit(category, r.unit());
        if (r.quantity() == null || r.quantity().signum() < 0 || r.quantity().compareTo(MAX_QUANTITY) >= 0
                || r.quantity().scale() > 2 && r.quantity().stripTrailingZeros().scale() > 2) {
            throw invalid("invalid-quantity", "The quantity is a number from 0 with at most 2 decimals");
        }
        if (r.periodQuantity() != null && r.periodQuantity().signum() < 0) {
            throw invalid("invalid-period-quantity", "The full-period quantity cannot be negative");
        }
        return store.stage(new StagedLine(null, r.importJobId(), r.rowNo(), period.id(), period.periodStart(),
                r.employmentId(), line == null ? null : line.assignmentKey(), from, to, r.legalEntityId(),
                line == null ? null : line.orgUnitId(), payGroup, line == null ? null : line.costCentreId(), category,
                unit, r.quantity(), r.periodQuantity(), "STAGED", null, null));
    }

    static String category(String raw) {
        String c = raw == null ? null : raw.strip().toUpperCase(Locale.ROOT);
        if (c == null || c.isEmpty()) {
            throw invalid("category-required", "The category is required (BRD §7.3, e.g. WORKED_DAYS)");
        }
        // sub-codes keep their own case (OT_HOURS.OT-WD-N): only the head is upper-cased
        int dot = raw.strip().indexOf('.');
        String value = dot < 0 ? c : raw.strip().substring(0, dot).toUpperCase(Locale.ROOT) + raw.strip().substring(dot);
        if (!CATEGORY.matcher(value).matches()) {
            throw invalid("invalid-category", "Category " + raw + " is not a valid category code (BRD §7.3)");
        }
        if (value.startsWith(ResultLines.RETRO_PREFIX)) {
            throw invalid("retro-not-importable", "Retro deltas are computed by T&A, they cannot be imported");
        }
        return value;
    }

    static String unit(String category, String raw) {
        String u = raw == null || raw.isBlank() ? null : raw.strip().toUpperCase(Locale.ROOT);
        if (u == null) {
            String head = category.contains(".") ? category.substring(0, category.indexOf('.')) : category;
            if (head.endsWith("_HOURS")) {
                return "HOUR";
            }
            if (head.endsWith("_DAYS")) {
                return "DAY";
            }
            throw invalid("unit-required", "Give the unit of category " + category + " (DAY, HOUR, MINUTE or COUNT)");
        }
        if (!UNITS.contains(u)) {
            throw invalid("invalid-unit", "The unit is DAY, HOUR, MINUTE or COUNT");
        }
        return u;
    }

    // ------------------------------------------------------------------------------------------------ publish

    /**
     * Publishes the staged rows of the job: one IMPORT version per period, all in this transaction. Idempotent: a job
     * already published returns its periods.
     */
    @AttTransactional
    public JobStatus publish(UUID importJobId) {
        List<StagedLine> all = store.lines(importJobId);
        if (all.stream().anyMatch(l -> "STAGED".equals(l.status()) && l.jobCommittedAt() == null)) {
            throw ApiException.conflict("import-not-committed", "Import job " + importJobId
                    + " has not been committed by the import engine; only a committed job is published");
        }
        Map<UUID, List<StagedLine>> byPeriod = all.stream().filter(l -> "STAGED".equals(l.status()))
                .collect(Collectors.groupingBy(StagedLine::periodId, LinkedHashMap::new, Collectors.toList()));
        for (var e : byPeriod.entrySet()) {
            publishPeriod(importJobId, e.getKey(), e.getValue());
        }
        return status(store.lines(importJobId), importJobId);
    }

    private void publishPeriod(UUID jobId, UUID periodId, List<StagedLine> staged) {
        Period p = periods.forUpdate(periodId).orElseThrow(() -> ApiException.notFound("Attendance period", periodId));
        if (!"OPEN".equals(p.status()) && !"IN_REVIEW".equals(p.status())) {
            throw ApiException.conflict("period-not-open", "The attendance period starting " + p.periodStart()
                    + " is " + p.status() + " now; reopen it, then publish the import again");
        }
        List<NewLine> lines = new ArrayList<>();
        for (StagedLine s : staged) {
            lines.add(new NewLine(s.employmentId(), s.assignmentKey(), s.segmentFrom(), s.segmentTo(),
                    s.legalEntityId(), s.orgUnitId(), s.payGroupId(), s.costCentreId(), s.category(), s.unit(),
                    s.quantity(), s.periodQuantity(), false, null, null, null, null,
                    "{\"importJobId\":\"" + jobId + "\",\"rowNo\":" + s.rowNo() + "}", null));
        }
        String checksum = ResultLines.checksum(lines.stream().map(l -> ResultLines.canonical(l.employmentId(),
                l.segmentFrom(), l.segmentTo(), l.category(), l.unit(), l.quantity(), l.periodQuantity(),
                l.precededByDayOt(), null, null)).toList());
        int version = p.currentVersion() + 1;
        UUID supersedes = results.results(p.id()).stream().filter(r -> "PERIOD".equals(r.scope()))
                .map(Result::id).findFirst().orElse(null);
        results.live(p.id(), null).ifPresent(r -> results.withdraw(r.id(), "superseded by imported v" + version));
        UUID resultId = results.insertResult(new NewResult(p.id(), p.periodStart(), "PERIOD", null, "REGULAR",
                version, p.periodStart(), p.periodEnd(), p.otPeriodStart(), p.otPeriodEnd(), checksum, null,
                RULE_VERSION, 0, supersedes, "IMPORT"), jobId);
        for (NewLine l : lines) {
            results.insertLine(resultId, p.periodStart(), l);
        }
        store.markPublished(jobId, p.id(), resultId);
        periods.close(p.id(), version);
        outbox.publish(new AttendancePeriodClosed(TenantContext.current().tenantId(), p.legalEntityId(), p.id(),
                p.payGroupId(), p.periodStart(), p.periodEnd(), resultId, version, "PERIOD", null, "IMPORT", checksum,
                lines.size(), 0));
        audit.record(new BusinessAction("IMPORT_RESULT", "att", "attendance_period", p.id(), p.legalEntityId(),
                Map.of("importJobId", jobId.toString(), "version", String.valueOf(version),
                        "lines", String.valueOf(lines.size()))));
    }

    /** Records that Core committed the job (own transaction, so it stays even when the publish fails). */
    @AttTransactional
    public int jobCommitted(UUID importJobId) {
        return store.markJobCommitted(importJobId);
    }

    // ------------------------------------------------------------------------------------------------ status

    /** The job's rows and periods; the caller needs {@code att.result.import} on the legal entities involved. */
    @AttTransactional(readOnly = true)
    public JobStatus statusInScope(UUID importJobId) {
        List<StagedLine> all = store.lines(importJobId);
        requireScope(all);
        return status(all, importJobId);
    }

    /** {@link #publish} for a caller: needs {@code att.result.import} on every legal entity of the job. */
    @AttTransactional
    public JobStatus publishInScope(UUID importJobId) {
        requireScope(store.lines(importJobId));
        return publish(importJobId);
    }

    private void requireScope(List<StagedLine> all) {
        for (UUID le : all.stream().map(StagedLine::legalEntityId).distinct().toList()) {
            if (!authz.can(AttendancePermissions.RESULT_IMPORT, new AuthorizationTarget.LegalEntity(le))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "legal-entity-not-in-scope",
                        "The import touches a legal entity outside your data scope");
            }
        }
    }

    private JobStatus status(List<StagedLine> all, UUID jobId) {
        int staged = (int) all.stream().filter(l -> "STAGED".equals(l.status())).count();
        int published = all.size() - staged;
        Map<UUID, List<StagedLine>> byResult = all.stream().filter(l -> l.resultId() != null)
                .collect(Collectors.groupingBy(StagedLine::resultId, LinkedHashMap::new, Collectors.toList()));
        List<PublishedPeriod> out = new ArrayList<>();
        for (var e : byResult.entrySet()) {
            Result r = results.result(e.getKey()).orElseThrow();
            out.add(new PublishedPeriod(r.periodId(), e.getValue().getFirst().periodStart(), r.id(), r.version(),
                    e.getValue().size()));
        }
        String status = all.isEmpty() ? "EMPTY" : staged > 0 ? "STAGED" : "PUBLISHED";
        return new JobStatus(jobId, status, staged, published, out);
    }

    private static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message);
    }
}
