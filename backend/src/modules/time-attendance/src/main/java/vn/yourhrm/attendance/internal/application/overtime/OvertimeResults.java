package vn.yourhrm.attendance.internal.application.overtime;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.application.overtime.OvertimeDays.DayContext;
import vn.yourhrm.attendance.internal.application.overtime.OvertimePolicy.Limits;
import vn.yourhrm.attendance.internal.application.timesheet.ActualOvertimeSink;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Bucket;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.Category;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.ResultRow;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.Placement;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.RequestRow;
import vn.yourhrm.attendance.internal.infra.overtime.OvertimeStore.ResultView;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Paid overtime per employment × work date × category ({@code att.overtime_result}, BR-ATT-041/042/044): the approved
 * requests of the day and the actual overtime from daily processing, each classified minute by minute, then
 * paid = min(approved, actual) per category (or approved, when the legal entity pays approved overtime as worked).
 *
 * <p>Daily processing pushes the actual intervals ({@link ActualOvertimeSink}); an approval change recalculates from
 * the actual minutes already stored. Days of a CLOSED / LOCKED period are not rewritten (retro deltas are §4.8).
 * The ledger's {@code paid_min} of the month and year is re-summed after every change.
 */
@Component
public class OvertimeResults implements ActualOvertimeSink {

    private static final Logger log = LoggerFactory.getLogger(OvertimeResults.class);

    private final OvertimeStore store;
    private final OvertimeDays days;
    private final OvertimePolicy policy;

    public OvertimeResults(OvertimeStore store, OvertimeDays days, OvertimePolicy policy) {
        this.store = store;
        this.days = days;
        this.policy = policy;
    }

    @Override
    @AttTransactional
    public void recordActual(UUID employmentId, LocalDate workDate, List<ActualOvertimeSink.Interval> actualOt) {
        Placement where = store.placement(employmentId, workDate).orElse(null);
        if (where == null) {
            return;
        }
        try {
            Limits limits = policy.limits(where.legalEntityId(), where.locationId(), workDate);
            DayContext ctx = days.of(employmentId, workDate, where.locationId());
            List<OvertimeClassifier.Interval> local = actualOt.stream().map(i -> ctx.local(i.start(), i.end()))
                    .toList();
            write(employmentId, workDate, where, limits, ctx, classify(local, ctx, limits));
        } catch (ApiException e) {
            if (!"legal-parameter-missing".equals(e.code())) {
                throw e;
            }
            log.warn("Overtime results of a day not computed: {}", e.getMessage());
        }
    }

    /** Recalculates the day from its approved requests and the actual minutes already stored. */
    @AttTransactional
    public void recompute(UUID employmentId, LocalDate workDate) {
        Placement where = store.placement(employmentId, workDate).orElse(null);
        if (where == null) {
            return;
        }
        Limits limits = policy.limits(where.legalEntityId(), where.locationId(), workDate);
        DayContext ctx = days.of(employmentId, workDate, where.locationId());
        List<Bucket> actual = new ArrayList<>();
        for (ResultView r : store.results(employmentId, workDate, workDate)) {
            if (r.actualMinutes() > 0) {
                actual.add(new Bucket(Category.ofCode(r.otCategory()), r.actualMinutes(), r.precededByDayOt()));
            }
        }
        write(employmentId, workDate, where, limits, ctx, actual);
    }

    private void write(UUID employmentId, LocalDate workDate, Placement where, Limits limits, DayContext ctx,
            List<Bucket> actual) {
        if (store.frozen(where.legalEntityId(), workDate)) {
            log.info("Overtime results of a day in a closed attendance period are left unchanged");
            return;
        }
        List<RequestRow> approved = store.requestsOfDay(employmentId, workDate, Set.of("APPROVED"));
        List<OvertimeClassifier.Interval> intervals = approved.stream().map(r -> ctx.local(r.plannedStartAt(), r.plannedEndAt()))
                .toList();
        List<Bucket> approvedBuckets = classify(intervals, ctx, limits);
        List<ResultRow> rows = OvertimeRules.paid(approvedBuckets, actual, limits.paidAsApproved());
        UUID requestId = approved.size() == 1 ? approved.getFirst().id() : null;
        store.replaceResults(employmentId, where.legalEntityId(), where.orgUnitId(), workDate, rows, requestId,
                limits.packId());
        store.refreshPaid(employmentId, workDate.withDayOfMonth(1), workDate.withDayOfYear(1));
    }

    static List<Bucket> classify(List<OvertimeClassifier.Interval> intervals, DayContext ctx, Limits limits) {
        return OvertimeClassifier.classify(intervals, ctx::dayType,
                OvertimePolicy.night(limits, ctx.nightStart(), ctx.nightEnd()));
    }
}
