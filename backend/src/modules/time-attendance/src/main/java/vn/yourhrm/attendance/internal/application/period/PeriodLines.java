package vn.yourhrm.attendance.internal.application.period;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.api.RetroDeltaCreated;
import vn.yourhrm.attendance.internal.application.schedule.WorkScheduleResolver;
import vn.yourhrm.attendance.internal.application.timesheet.TimesheetProcessor;
import vn.yourhrm.attendance.internal.application.timesheet.TimesheetProcessor.Preview;
import vn.yourhrm.attendance.internal.domain.period.ResultLines;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.CodeInfo;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.CodeQty;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.DayFacts;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Delta;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Line;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Segment;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.Standard;
import vn.yourhrm.attendance.internal.domain.schedule.StandardDays;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore.Period;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.ClosingDay;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.NewLine;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore.RetroCandidate;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;

/**
 * Computes the lines of a result version (BRD §7.3) from the stored days of the period, the paid overtime and the
 * standard days ({@link ResultLines}), and the retro deltas of LOCKED periods (BR-ATT-071) from days recomputed in
 * memory ({@link TimesheetProcessor#preview}). Runs inside the caller's T&amp;A transaction.
 */
@Component
class PeriodLines {

    /** Lines of a version, with what the header stamps. */
    record Built(List<NewLine> lines, int overrideDays, UUID legalPackId, Set<UUID> employments) {
    }

    /** Retro deltas of one employment x original period, ready to insert. */
    record Retro(RetroCandidate request, List<UUID> requestIds, UUID originalResultId, List<NewLine> lines,
            List<RetroDeltaCreated.Item> items) {
    }

    private final AttendanceResultStore store;
    private final AttendancePeriodStore periods;
    private final TimesheetStore timesheets;
    private final WorkScheduleResolver resolver;
    private final TimesheetProcessor processor;

    PeriodLines(AttendanceResultStore store, AttendancePeriodStore periods, TimesheetStore timesheets,
            WorkScheduleResolver resolver, TimesheetProcessor processor) {
        this.store = store;
        this.periods = periods;
        this.timesheets = timesheets;
        this.resolver = resolver;
        this.processor = processor;
    }

    /**
     * Lines of the stored days of {@code [p.start, to]}, of one employment or of every employment of the period except
     * {@code exclude}.
     */
    Built regular(Period p, UUID employmentId, LocalDate to, Set<UUID> exclude) {
        Map<String, CodeInfo> codes = codes();
        Map<UUID, List<DayFacts>> byEmployment = new LinkedHashMap<>();
        int overrides = 0;
        Set<UUID> packs = new HashSet<>();
        for (ClosingDay d : store.days(p.id(), p.payGroupId(), employmentId, p.periodStart(), to)) {
            if (exclude.contains(d.employmentId())) {
                continue;
            }
            byEmployment.computeIfAbsent(d.employmentId(), k -> new ArrayList<>()).add(d.facts());
            overrides += d.manualOverride() ? 1 : 0;
            if (d.legalPackId() != null) {
                packs.add(d.legalPackId());
            }
        }
        List<NewLine> out = new ArrayList<>();
        byEmployment.forEach((e, days) -> {
            for (Line l : linesOf(p, e, days, codes, to)) {
                out.add(newLine(e, l.segment(), l.category(), l.unit(), l.quantity(), l.periodQuantity(),
                        l.precededByDayOt(), null, null, null, null, null));
            }
        });
        return new Built(out, overrides, packs.size() == 1 ? packs.iterator().next() : null,
                byEmployment.keySet());
    }

    /**
     * Retro deltas for the given requests: per employment x original period, the days recomputed now minus what was
     * handed over (locked version + live retro lines). Requests without a difference come back with no lines.
     */
    List<Retro> retro(List<RetroCandidate> requests) {
        Map<String, CodeInfo> codes = codes();
        Map<String, List<RetroCandidate>> groups = new LinkedHashMap<>();
        for (RetroCandidate r : requests) {
            groups.computeIfAbsent(r.employmentId() + "|" + r.periodId(), k -> new ArrayList<>()).add(r);
        }
        List<Retro> out = new ArrayList<>();
        for (List<RetroCandidate> g : groups.values()) {
            RetroCandidate r = g.getFirst();
            List<UUID> ids = g.stream().map(RetroCandidate::id).toList();
            UUID original = store.lockedResultOf(r.periodId(), r.employmentId()).orElse(null);
            Period p = periods.find(r.periodId()).orElseThrow();
            List<DayFacts> fresh = new ArrayList<>();
            for (Preview v : processor.preview(r.employmentId(), r.periodStart(), r.periodEnd())) {
                fresh.add(facts(v));
            }
            List<Line> freshLines = linesOf(p, r.employmentId(), fresh, codes, p.periodEnd());
            List<Delta> deltas = ResultLines.diff(freshLines, store.handedOver(r.periodId(), r.employmentId()));
            List<NewLine> lines = new ArrayList<>();
            List<RetroDeltaCreated.Item> items = new ArrayList<>();
            String src = "{\"retroRequestIds\":[" + String.join(",", ids.stream().map(i -> "\"" + i + "\"")
                    .toList()) + "]}";
            for (Delta d : deltas) {
                lines.add(newLine(r.employmentId(), d.segment(), ResultLines.RETRO_PREFIX + d.category(), d.unit(),
                        d.quantity(), null, d.precededByDayOt(), r.periodId(), original, d.segment().from(),
                        "Days of the locked period " + r.periodStart() + " changed after the lock (recalculation)",
                        src));
                items.add(new RetroDeltaCreated.Item(d.segment().from(), d.category(), d.unit(), d.quantity(),
                        d.precededByDayOt()));
            }
            out.add(new Retro(r, ids, original, lines, items));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private List<Line> linesOf(Period p, UUID employmentId, List<DayFacts> days, Map<String, CodeInfo> codes,
            LocalDate to) {
        if (days.isEmpty()) {
            return List.of();
        }
        LocalDate otFrom = p.otPeriodStart() != null ? p.otPeriodStart() : p.periodStart();
        LocalDate otTo = p.otPeriodEnd() != null ? p.otPeriodEnd() : to;
        if (otTo.isAfter(to) && p.otPeriodEnd() == null) {
            otTo = to;
        }
        return ResultLines.lines(withPayGroup(days, p.payGroupId()), codes,
                store.overtime(employmentId, otFrom, otTo), standard(p, employmentId, days, to));
    }

    private static List<DayFacts> withPayGroup(List<DayFacts> days, UUID payGroupId) {
        List<DayFacts> out = new ArrayList<>(days.size());
        for (DayFacts d : days) {
            out.add(d.payGroupId() != null || payGroupId == null ? d : new DayFacts(d.date(), d.employed(),
                    d.assignmentKey(), d.legalEntityId(), d.orgUnitId(), payGroupId, d.costCentreId(), d.codes(),
                    d.nightHours(), d.lateMin(), d.earlyMin(), d.exceptions(), d.workedHours(), d.shiftGroupCode()));
        }
        return out;
    }

    /** STD_DAYS / STD_HOURS per result segment (Payroll's method, D1 shares), or null without a method. */
    private Standard standard(Period p, UUID employmentId, List<DayFacts> days, LocalDate to) {
        if (p.stdDayMethod() == null) {
            return null;
        }
        List<Segment> segments = ResultLines.segments(withPayGroup(days, p.payGroupId()));
        Map<UUID, LocalDate> idToFrom = new HashMap<>();
        List<StandardDays.Day> inputs = new ArrayList<>();
        for (StandardDays.Day d : resolver.standardDayInputs(employmentId, p.periodStart(), p.periodEnd())) {
            UUID seg = null;
            if (!d.date().isAfter(to)) {
                for (Segment s : segments) {
                    if (s.employed() && !d.date().isBefore(s.from()) && !d.date().isAfter(s.to())) {
                        seg = UUID.nameUUIDFromBytes(s.from().toString().getBytes(StandardCharsets.UTF_8));
                        idToFrom.put(seg, s.from());
                        break;
                    }
                }
            }
            inputs.add(new StandardDays.Day(d.date(), seg, d.work(), d.dayValue(), d.stdHours()));
        }
        StandardDays.Result r = StandardDays.compute(p.stdDayMethod(), p.stdDayValue(), inputs);
        Map<LocalDate, BigDecimal[]> by = new HashMap<>();
        for (StandardDays.SegmentValue v : r.segments()) {
            by.put(idToFrom.get(v.segmentId()), new BigDecimal[] {v.stdDays(), v.stdHours()});
        }
        return new Standard(by, r.periodStdDays(), r.periodStdHours());
    }

    private Map<String, CodeInfo> codes() {
        Map<String, CodeInfo> out = new HashMap<>();
        timesheets.effectiveCodes().forEach((code, c) -> out.put(code,
                new CodeInfo(c.kind(), c.payrollCategory(), c.payrollSubCode(), c.siNonwork())));
        return out;
    }

    private DayFacts facts(Preview v) {
        var r = v.result();
        var pl = v.placement();
        List<CodeQty> codes = r.codes().stream().map(c -> new CodeQty(c.code(), c.qty())).toList();
        return new DayFacts(v.date(), !"NOT_EMPLOYED".equals(r.dayStatus()), pl.assignmentKey(), pl.legalEntityId(),
                pl.orgUnitId(), pl.payGroupId(), store.costCentre(pl.assignmentKey(), v.date()).orElse(null), codes,
                r.nightHours(), r.lateMin(), r.earlyMin(), r.exceptions(), r.workedHours(), v.shiftGroupCode());
    }

    private static NewLine newLine(UUID employmentId, Segment s, String category, String unit, BigDecimal q,
            BigDecimal pq, boolean preceded, UUID originalPeriodId, UUID originalResultId, LocalDate originalFrom,
            String reason, String sourceRef) {
        return new NewLine(employmentId, s.assignmentKey(), s.from(), s.to(), s.legalEntityId(), s.orgUnitId(),
                s.payGroupId(), s.costCentreId(), category, unit, q, pq, preceded, originalPeriodId, originalResultId,
                originalFrom, reason, sourceRef);
    }
}
