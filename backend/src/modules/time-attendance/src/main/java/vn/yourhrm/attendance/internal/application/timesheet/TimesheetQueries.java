package vn.yourhrm.attendance.internal.application.timesheet;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.attendance.internal.infra.AttDb;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.PunchRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CorrectionRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.DayRow;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Reading timesheets (US-ATT-032): the grid of a legal entity / org unit with status, code and exception filters, the
 * days of one employment, and the drill-down of a day (its punches and corrections; the punch read is audited,
 * NFR-ATT-008). Recalculation on demand (BR-ATT-038) for an employment or a whole legal entity in scope.
 */
@Service
public class TimesheetQueries {

    static final int MAX_RANGE_DAYS = 62;

    public record DayDetail(DayRow day, List<PunchRow> punches, List<CorrectionRow> corrections) {
    }

    private final TimesheetStore store;
    private final PunchStore punches;
    private final TimesheetProcessor processor;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final TransactionTemplate readTx;

    public TimesheetQueries(TimesheetStore store, PunchStore punches, TimesheetProcessor processor,
            AuthorizationService authz, AuditLog audit, BusinessClock clock,
            @Qualifier(AttDb.TX) PlatformTransactionManager attTm) {
        this.readTx = new TransactionTemplate(attTm);
        this.readTx.setReadOnly(true);
        this.store = store;
        this.punches = punches;
        this.processor = processor;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
    }

    @AttTransactional(readOnly = true)
    public List<DayRow> grid(UUID legalEntityId, UUID orgUnitId, LocalDate from, LocalDate to, String status,
            String exception, boolean blockingOnly, int limit, int offset) {
        range(from, to);
        return store.grid(legalEntityId, orgUnitId, from, to, TimesheetInputs.upper(status),
                TimesheetInputs.upper(exception), blockingOnly,
                authz.scopeFor(AttendancePermissions.TIMESHEET_VIEW, clock.today()), Math.clamp(limit, 1, 5000),
                Math.max(0, offset));
    }

    @AttTransactional(readOnly = true)
    public List<DayRow> ofEmployment(UUID employmentId, LocalDate from, LocalDate to) {
        range(from, to);
        requireEmployee(AttendancePermissions.TIMESHEET_VIEW, employmentId, to);
        return store.days(employmentId, from, to);
    }

    @AttTransactional(readOnly = true)
    public DayDetail day(UUID employmentId, LocalDate date) {
        var p = requireEmployee(AttendancePermissions.TIMESHEET_VIEW, employmentId, date);
        DayRow day = store.day(employmentId, date)
                .orElseThrow(() -> ApiException.notFound("Timesheet day", employmentId + "/" + date));
        List<PunchRow> rows = punches.punchesOfEmployment(employmentId, date.minusDays(1), date.plusDays(1));
        audit.record(new BusinessAction("VIEW", "att", "raw_punch", employmentId, p.legalEntityId(),
                Map.of("from", date.minusDays(1).toString(), "to", date.plusDays(1).toString(),
                        "rows", String.valueOf(rows.size()))));
        return new DayDetail(day, rows, store.corrections(employmentId, date, date, false));
    }

    /** Recomputes the employments (or every employment of the legal entity) in the caller's scope. */
    public TimesheetProcessor.Summary recompute(UUID legalEntityId, List<UUID> employmentIds, LocalDate from,
            LocalDate to) {
        range(from, to);
        List<UUID> targets = new ArrayList<>();
        if (employmentIds != null && !employmentIds.isEmpty()) {
            readTx.executeWithoutResult(st -> employmentIds.forEach(
                    e -> requireEmployee(AttendancePermissions.TIMESHEET_UPDATE, e, to)));
            targets.addAll(employmentIds.stream().distinct().toList());
        } else if (legalEntityId != null) {
            if (!authz.can(AttendancePermissions.TIMESHEET_UPDATE, new AuthorizationTarget.LegalEntity(legalEntityId))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "legal-entity-not-in-scope",
                        "The legal entity is outside your data scope");
            }
            targets.addAll(processor.employmentsOf(legalEntityId, from, to));
        } else {
            throw TimesheetQueriesSupport.invalid("target-required", "Give the employments or the legal entity");
        }
        return processor.recomputeAll(targets, from, to);
    }

    private PunchStore.Placement requireEmployee(String permission, UUID employmentId, LocalDate date) {
        PunchStore.Placement p = punches.placement(employmentId, date)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (!authz.can(permission, new AuthorizationTarget.Employee(employmentId, p.legalEntityId(), p.orgUnitId(),
                p.locationId()), date)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "employment-not-in-scope",
                    "Employment " + employmentId + " is outside your data scope");
        }
        return p;
    }

    static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw TimesheetQueriesSupport.invalid("invalid-range", "Give from and to (to on or after from)");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw TimesheetQueriesSupport.invalid("range-too-long", "The range is at most " + MAX_RANGE_DAYS + " days");
        }
    }

    private static final class TimesheetQueriesSupport {
        static ApiException invalid(String code, String message) {
            return ApiException.unprocessable(code, message);
        }
    }
}
