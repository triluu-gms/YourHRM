package vn.yourhrm.attendance.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payroll's synchronous, in-process API to the Attendance Result (ATT-P-01) and the close hold (ATT-P-17, BR-ATT-079,
 * ADR-0019). Allowed direction payroll → time-attendance (ADR-0001). Evaluated in the current {@code TenantContext}
 * without a permission check (module API; Payroll checks its own user permissions).
 *
 * <p>{@code att.attendance_result_hold} is the only authority for hold state. Acquire succeeds only when the period is
 * CLOSED and the requested version is the current one (EMPLOYMENT scope: the result is PUBLISHED and the period is not
 * LOCKED); otherwise it is refused and no hold exists. Acquire and release are idempotent by run id + version. Holds
 * never expire.
 */
public interface AttendanceResults {

    /** Reasons Payroll gives when it releases a hold. UNLOCKED is not one: it comes with the run status. */
    List<String> RELEASE_REASONS = List.of("REJECTED", "WITHDRAWN", "CANCELLED", "REOPEN_REQUESTED");

    /**
     * A result version.
     *
     * @param scope        PERIOD or EMPLOYMENT
     * @param kind         REGULAR, EARLY_CLOSE or OFF_CYCLE_RETRO
     * @param status       PUBLISHED, WITHDRAWN or LOCKED
     * @param closeCause   CLOSE, RECLOSE, EARLY_CLOSE, ...
     */
    record Result(UUID id, UUID periodId, UUID legalEntityId, UUID payGroupId, LocalDate periodStart,
            LocalDate periodEnd, String scope, UUID employmentId, String kind, int version, String status,
            LocalDate attDateFrom, LocalDate attDateTo, LocalDate otDateFrom, LocalDate otDateTo, String checksum,
            Integer lineCount, String closeCause, String ruleVersion, Instant publishedAt) {
    }

    /**
     * One line (BRD §7.3). Retro lines carry {@code retro}, the original period / result / segment and the reason;
     * their category is {@code RETRO.<original category>} and their quantity is signed. The segment keys beyond the
     * placement (schedule pattern, employee group, FTE) are null on imported lines and on lines before v0.10.
     */
    record Line(UUID employmentId, UUID assignmentKey, LocalDate segmentFrom, LocalDate segmentTo,
            UUID legalEntityId, UUID orgUnitId, UUID payGroupId, UUID costCentreId, String category, String unit,
            BigDecimal quantity, BigDecimal periodQuantity, boolean precededByDayOt, boolean retro,
            UUID originalPeriodId, UUID originalResultId, LocalDate originalSegmentFrom, String retroReason,
            UUID schedulePatternId, String employeeGroupCode, BigDecimal fte) {
    }

    /** @param status ACTIVE, RELEASED or CONSUMED */
    record Hold(UUID id, UUID periodId, UUID resultId, int resultVersion, String scope, UUID employmentId,
            UUID payrollRunId, String status, Instant heldAt, String releaseReason, Instant releasedAt,
            Instant consumedAt) {
    }

    /**
     * @param granted       the hold exists for this run and version (also when it already existed: idempotent)
     * @param refusalCode   when not granted: period-not-closed, version-not-current, result-not-found,
     *                      held-by-other-run, period-locked, hold-not-found, invalid-reason
     */
    record HoldOutcome(boolean granted, String refusalCode, String message, Hold hold) {

        public static HoldOutcome refused(String code, String message) {
            return new HoldOutcome(false, code, message, null);
        }
    }

    /** The live (PUBLISHED or LOCKED) version of the period, or of one employment's early close. */
    Optional<Result> currentResult(UUID periodId, UUID employmentId);

    Optional<Result> result(UUID resultId);

    List<Line> lines(UUID resultId);

    /** @param employmentId null for the PERIOD scope, else the leaver's EMPLOYMENT-scope result */
    HoldOutcome acquireHold(UUID periodId, int resultVersion, UUID payrollRunId, UUID employmentId);

    /** @param reason one of {@link #RELEASE_REASONS} */
    HoldOutcome releaseHold(UUID periodId, UUID payrollRunId, String reason, UUID employmentId);

    Optional<Hold> activeHold(UUID periodId, UUID employmentId);
}
