package vn.yourhrm.attendance.internal.application.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.period.PeriodService;
import vn.yourhrm.attendance.internal.application.period.PeriodService.Checklist;
import vn.yourhrm.attendance.internal.application.period.PeriodService.PeriodView;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.report.ReportStore;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.RetroRow;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.RetroTotal;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Period reports of BRD 03 §8: R-08 the close status per legal entity × pay group with the outstanding checklist
 * items of every OPEN / IN_REVIEW period (the checklist of {@link PeriodService}, BR-ATT-074, not recomputed here), and
 * R-09 the retro adjustments (BR-ATT-071) grouped by their original period. Scope of {@code att.period.view}.
 */
@Service
public class PeriodReports {

    private static final Set<String> STATUSES = Set.of("OPEN", "IN_REVIEW", "CLOSED", "LOCKED");

    public record Outstanding(String code, String label, long count, boolean waivable) {
    }

    /**
     * @param overdue        the close deadline passed and the period is not closed yet
     * @param pendingRetro   employments whose days changed after the lock, not yet carried by a result
     */
    public record CloseStatus(PeriodView period, String statusLabel, boolean overdue, Boolean canClose,
            List<Outstanding> outstanding, long outstandingTotal, long pendingRetro) {
    }

    public record RetroReport(List<RetroTotal> totals, ReportPage<RetroRow> page) {
    }

    private final PeriodService periods;
    private final ReportStore store;
    private final AuthorizationService authz;
    private final BusinessClock clock;

    public PeriodReports(PeriodService periods, ReportStore store, AuthorizationService authz, BusinessClock clock) {
        this.periods = periods;
        this.store = store;
        this.authz = authz;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------ R-08 close

    @AttTransactional(readOnly = true)
    public ReportPage<CloseStatus> closeStatus(UUID legalEntityId, UUID payGroupId, String status, LocalDate from,
            LocalDate to, String lang, Integer limit, Integer offset) {
        String st = ReportSupport.oneOf(status, "status", STATUSES);
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid-range", "to must be on or after from");
        }
        String l = ReportLabels.lang(lang);
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        List<PeriodView> all = periods.list(legalEntityId, payGroupId, st, from, to);   // scope-filtered there
        List<PeriodView> slice = all.stream().skip(off).limit(lim + 1L).toList();
        ReportPage<PeriodView> page = ReportPage.of(slice, lim, off);
        Map<UUID, Long> retro = store.pendingRetroRequests(page.items().stream().map(PeriodView::id).toList());
        LocalDate today = clock.today();
        return page.map(p -> {
            boolean open = "OPEN".equals(p.status()) || "IN_REVIEW".equals(p.status());
            List<Outstanding> items = List.of();
            Boolean canClose = null;
            if (open) {
                Checklist c = periods.checklist(p.id(), null);
                canClose = c.canClose();
                items = c.items().stream()
                        .map(i -> new Outstanding(i.code(), ReportLabels.text(i.code(), l), i.count(), i.waivable()))
                        .toList();
            }
            boolean overdue = open && p.closeDeadline() != null && p.closeDeadline().isBefore(today);
            return new CloseStatus(p, ReportLabels.text(p.status(), l), overdue, canClose, items,
                    items.stream().mapToLong(Outstanding::count).sum(), retro.getOrDefault(p.id(), 0L));
        });
    }

    // ------------------------------------------------------------------------------------------ R-09 retro

    /**
     * Retro lines carried by later results, by original period ({@code from} / {@code to} filter the original period
     * start). Lines of withdrawn results (reopened periods) are left out unless asked for.
     */
    @AttTransactional(readOnly = true)
    public RetroReport retro(UUID legalEntityId, UUID payGroupId, UUID originalPeriodId, LocalDate from,
            LocalDate to, UUID employmentId, boolean includeWithdrawn, Integer limit, Integer offset) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid-range", "to must be on or after from");
        }
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        ScopePredicate scope = authz.scopeFor(AttendancePermissions.PERIOD_VIEW, clock.today());
        List<RetroRow> rows = store.retroLines(legalEntityId, payGroupId, originalPeriodId, from, to, employmentId,
                includeWithdrawn, scope, lim + 1, off);
        List<RetroTotal> totals = store.retroTotals(legalEntityId, payGroupId, originalPeriodId, from, to,
                employmentId, includeWithdrawn, scope);
        return new RetroReport(totals, ReportPage.of(rows, lim, off));
    }
}
