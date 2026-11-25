package vn.yourhrm.attendance.internal.application.punch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.core.api.ImportType.ImportContext;
import vn.yourhrm.core.api.ImportType.ImportRow;
import vn.yourhrm.core.api.ImportType.RowMessage;
import vn.yourhrm.people.api.EmployeeDirectory;
import vn.yourhrm.people.api.EmployeeDirectory.EmployeeSnapshot;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Plumbing shared by T&amp;A's import types (US-ATT-014/016/017).
 *
 * <p><b>Transactions.</b> Core calls {@code validate} / {@code commit} inside its own transaction on Core's pool; the
 * {@code att} writes cannot join it (ADR-0021). Each row is written in its OWN T&amp;A transaction ({@link #write}) and
 * is idempotent (raw punches by dedup key, mappings by natural key), so re-running a file after a failure completes it
 * without duplicates. Validation ({@link #dryRun}) runs the same write path in a T&amp;A transaction that is always
 * rolled back, so the dry run reports exactly what the commit would do (including a month without a partition).
 *
 * <p><b>Per-job state.</b> Rows arrive in file order on one worker; the state of a pass (validate / commit) is reset
 * when a row number does not increase (a new pass starts).
 */
@Component
public class PunchImportSupport {

    private static final Logger log = LoggerFactory.getLogger(PunchImportSupport.class);

    private final TransactionTemplate rowTx;
    private final ObjectProvider<EmployeeDirectory> directory;
    private final BusinessClock clock;
    private final Map<String, Map<String, Object>> state = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> e) {
                    return size() > 64;
                }
            });

    public PunchImportSupport(@Qualifier(AttDb.TX) PlatformTransactionManager attTm,
            ObjectProvider<EmployeeDirectory> directory, BusinessClock clock) {
        this.rowTx = new TransactionTemplate(attTm);
        this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.directory = directory;
        this.clock = clock;
    }

    public BusinessClock clock() {
        return clock;
    }

    // ------------------------------------------------------------------------------------------------ transactions

    /** One row's writes in their own T&amp;A transaction. */
    public <T> T write(Supplier<T> work) {
        return rowTx.execute(s -> work.get());
    }

    /** {@code work} in a rolled-back T&amp;A transaction; its warnings, or the error it raised as a row message. */
    public List<RowMessage> dryRun(Supplier<List<RowMessage>> work) {
        List<RowMessage> out = new ArrayList<>();
        try {
            rowTx.executeWithoutResult(s -> {
                s.setRollbackOnly();
                out.addAll(work.get());
            });
        } catch (RuntimeException ex) {
            out.add(error(null, ex));
        }
        return out;
    }

    /** A row error from a business or database exception (no personal data in the message). */
    public static RowMessage error(String field, RuntimeException ex) {
        if (ex instanceof ApiException a) {
            return RowMessage.error(field, a.code(), a.getMessage(), a.getMessage());
        }
        if (ex instanceof DataIntegrityViolationException d) {
            String m = String.valueOf(d.getMostSpecificCause().getMessage());
            if (m.contains("no partition of relation")) {
                return RowMessage.error(field, "punch-month-not-open",
                        "Tháng của lượt chấm chưa được mở để lưu dữ liệu chấm công (liên hệ quản trị hệ thống)",
                        "The punch's month is not open for punch storage yet (contact the operator)");
            }
            return RowMessage.error(field, "invalid-data", "Dữ liệu vi phạm ràng buộc của cơ sở dữ liệu",
                    "The data violates a database rule");
        }
        log.warn("T&A import row failed unexpectedly: {}", ex.toString());
        return RowMessage.error(field, "unexpected-error", "Lỗi không xác định: " + ex.getClass().getSimpleName(),
                "Unexpected error: " + ex.getClass().getSimpleName());
    }

    public static boolean hasError(List<RowMessage> messages) {
        return messages.stream().anyMatch(m -> "ERROR".equals(m.severity()));
    }

    // ---------------------------------------------------------------------------------------------------- state

    /** State of one pass ("V" validate, "C" commit) of a job; a fresh map when a new pass starts. */
    public Map<String, Object> state(ImportContext ctx, ImportRow row, String phase) {
        String key = ctx.jobId() + ":" + phase;
        Map<String, Object> s = state.get(key);
        Integer last = s == null ? null : (Integer) s.get("$row");
        if (s == null || last == null || row.rowNo() <= last) {
            s = new HashMap<>();
            state.put(key, s);
        }
        s.put("$row", row.rowNo());
        return s;
    }

    public LocalDate asOf(ImportContext ctx) {
        return ctx.asOfDate() != null ? ctx.asOfDate() : clock.today();
    }

    // ----------------------------------------------------------------------------------------- employee numbers

    /**
     * The employment with {@code employeeNo} in the job's legal entity, in the importer's {@code hr.employee.view}
     * scope (People's {@link EmployeeDirectory}); loaded once per pass. Empty when unknown or not visible.
     */
    @SuppressWarnings("unchecked")
    public UUID employmentByNumber(ImportContext ctx, Map<String, Object> st, String employeeNo) {
        if (ctx.legalEntityId() == null) {
            throw ApiException.unprocessable("legal-entity-required",
                    "Rows identified by employee number need the import job's legal entity");
        }
        Map<String, UUID> byNo = (Map<String, UUID>) st.computeIfAbsent("$employees", k -> load(ctx));
        return byNo.get(employeeNo.strip().toUpperCase(Locale.ROOT));
    }

    private Map<String, UUID> load(ImportContext ctx) {
        EmployeeDirectory dir = directory.getIfAvailable();
        Map<String, UUID> out = new HashMap<>();
        if (dir == null) {
            return out;
        }
        Instant now = Instant.now();
        for (int offset = 0;; offset += EmployeeDirectory.MAX_BATCH) {
            List<EmployeeSnapshot> page = dir.snapshots(ctx.legalEntityId(), asOf(ctx), now,
                    EmployeeDirectory.MAX_BATCH, offset);
            for (EmployeeSnapshot s : page) {
                if (s.employment().employeeNo() != null) {
                    out.put(s.employment().employeeNo().toUpperCase(Locale.ROOT), s.employment().employmentId());
                }
            }
            if (page.size() < EmployeeDirectory.MAX_BATCH) {
                return out;
            }
        }
    }
}
