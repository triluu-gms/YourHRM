package vn.yourhrm.people.internal.application.onboarding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.ImportType.ImportContext;
import vn.yourhrm.core.api.ImportType.RowMessage;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.onboarding.OpeningBalanceLocks;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Shared plumbing of People's opening-balance import types (US-PPL-089..092).
 *
 * <p><b>Transactions.</b> Core calls {@code validate} / {@code commit} inside its own transaction on Core's pool; the
 * People writes cannot join it (ADR-0021, separate pools and roles). Each row is therefore written in its OWN People
 * transaction ({@link #write}); a failing row rolls back its own People writes and fails Core's commit (job FAILED),
 * while the rows committed before it stay. Every row is idempotent by natural key, so re-running the same file
 * completes the load without duplicates. Validation ({@link #dryRun}) runs the very same write path (the streams' own
 * services and rules) in a People transaction that is always rolled back: errors are exactly what the commit would
 * raise. People's in-process listeners run AFTER_COMMIT and do not fire for a rolled-back dry run.
 */
@Component
public class OnboardingSupport {

    private static final Logger log = LoggerFactory.getLogger(OnboardingSupport.class);
    private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([a-z0-9_]+)\"");

    /** An employment found by its employee number. */
    public record Emp(UUID id, UUID personId, UUID legalEntityId, String employeeNo, LocalDate hireDate,
            LocalDate endDate, String status, String source) {
    }

    private final HrDb db;
    private final TransactionTemplate rowTx;
    private final TransactionTemplate nested;
    private final ObjectProvider<OpeningBalanceLocks> locks;
    private final BusinessClock clock;
    /** In-file state per job and phase (validate / commit); rows arrive in file order on one worker. */
    private final Map<String, Map<String, Object>> state = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> e) {
                    return size() > 64;
                }
            });

    public OnboardingSupport(HrDb db, @Qualifier(HrDb.TX) PlatformTransactionManager hrTm,
            ObjectProvider<OpeningBalanceLocks> locks, BusinessClock clock) {
        this.db = db;
        this.rowTx = new TransactionTemplate(hrTm);
        this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.nested = new TransactionTemplate(hrTm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.locks = locks;
        this.clock = clock;
    }

    public HrDb db() {
        return db;
    }

    public static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    public static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    public LocalDate asOf(ImportContext ctx) {
        return ctx.asOfDate() != null ? ctx.asOfDate() : clock.today();
    }

    public LocalDate today() {
        return clock.today();
    }

    // ------------------------------------------------------------------------------------------------ transactions

    /** One row's writes in their own People transaction. */
    public <T> T write(Supplier<T> work) {
        return rowTx.execute(s -> work.get());
    }

    /**
     * Runs {@code work} (the row's write path) in a People transaction that is rolled back; returns its warnings,
     * or the error it raised as a row message.
     */
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

    /** {@code work} in a savepoint: its failure rolls back only its own writes and returns {@code fallback}. */
    public <T> T tryRun(String what, Supplier<T> work, T fallback) {
        try {
            return nested.execute(s -> work.get());
        } catch (RuntimeException ex) {
            log.warn("{} failed and was rolled back to its savepoint: {}", what, ex.getMessage());
            return fallback;
        }
    }

    /** A row error from a business exception (code and message of the stream's rule; no P3 in them). */
    public static RowMessage error(String field, RuntimeException ex) {
        if (ex instanceof ApiException a) {
            return RowMessage.error(field, a.code(), a.getMessage(), a.getMessage());
        }
        if (ex instanceof DataIntegrityViolationException d) {
            Matcher m = CONSTRAINT.matcher(String.valueOf(d.getMostSpecificCause().getMessage()));
            String c = m.find() ? m.group(1) : "database-rule";
            return RowMessage.error(field, "invalid-data", "Dữ liệu vi phạm ràng buộc " + c,
                    "The data violates database rule " + c);
        }
        log.warn("Opening-balance row failed unexpectedly: {}", ex.toString());
        return RowMessage.error(field, "unexpected-error", "Lỗi không xác định: " + ex.getClass().getSimpleName(),
                "Unexpected error: " + ex.getClass().getSimpleName());
    }

    public static RowMessage err(String field, String code, String vi, String en) {
        return RowMessage.error(field, code, vi, en);
    }

    public static RowMessage warn(String field, String code, String vi, String en) {
        return RowMessage.warning(field, code, vi, en);
    }

    public static boolean hasError(List<RowMessage> messages) {
        return messages.stream().anyMatch(m -> "ERROR".equals(m.severity()));
    }

    // ------------------------------------------------------------------------------------------------- go-live lock

    /** Opening balances of the legal entity are locked after go-live (BR-SAAS-088, BR-CORE-114). */
    public boolean locked(UUID legalEntityId) {
        OpeningBalanceLocks l = locks.getIfAvailable();
        return l != null && l.isLocked(tenant(), legalEntityId);
    }

    public void requireUnlocked(UUID legalEntityId) {
        if (locked(legalEntityId)) {
            throw ApiException.conflict("opening-balance-locked",
                    "Opening balances are locked for this legal entity (it has gone live)");
        }
    }

    // ------------------------------------------------------------------------------------------------------ lookups

    /** The employment with {@code employeeNo} (unique in the tenant, case-insensitive here). */
    public Optional<Emp> employment(String employeeNo) {
        if (employeeNo == null) {
            return Optional.empty();
        }
        return db.jdbc().sql("""
                        select id, person_id, legal_entity_id, employee_no, hire_date, end_date, status, source
                          from hr.employment where tenant_id = :t and upper(employee_no) = upper(:no)""")
                .param("t", tenant()).param("no", employeeNo.strip())
                .query((rs, n) -> new Emp(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class), rs.getString(7), rs.getString(8)))
                .optional();
    }

    /** The employment or 422 {@code employee-not-found} (load the master sheet first). */
    public Emp requireEmployment(String employeeNo) {
        return employment(employeeNo).orElseThrow(() -> ApiException.unprocessable("employee-not-found",
                "Employee " + employeeNo + " is not loaded; commit the " + OnboardingKeys.MASTER + " sheet first"));
    }

    // ---------------------------------------------------------------------------------------------------- in-file

    /**
     * Per-job state of one phase ("V" validate, "C" commit). Reset at the first row of a pass (no business key seen
     * yet), so a re-validation starts clean.
     */
    public Map<String, Object> state(ImportContext ctx, String phase) {
        String key = ctx.jobId() + ":" + phase;
        if (ctx.seenKeys().isEmpty()) {
            state.put(key, new HashMap<>());
        }
        return state.computeIfAbsent(key, k -> new HashMap<>());
    }
}
