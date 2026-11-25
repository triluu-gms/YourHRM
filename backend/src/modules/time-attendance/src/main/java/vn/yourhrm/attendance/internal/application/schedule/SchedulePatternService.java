package vn.yourhrm.attendance.internal.application.schedule;

import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.code;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.invalid;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.name;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.optionalText;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.stale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.PatternData;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.PatternDay;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.PatternRow;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.ShiftRow;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Weekly schedule patterns (BR-ATT-011/014, US-ATT-009): a cycle of 7 days (5-day, 6-day or 5.5-day week with a half
 * Saturday shift) or 14 days (alternating Saturdays), anchored on a Monday. Each day is WORK (with a shift), REST (a
 * weekly rest day) or OFF (not worked but not a rest day, e.g. the free Saturday of an alternating week).
 *
 * <p>The weekly rest days ({@code weekly_rest_days}) are derived from the REST days and must be the same weekdays in
 * every week of the cycle; they need not be Sunday. They drive the OT category and the substitute day of a holiday
 * (BR-ATT-003). Every week needs at least one rest day (VN-05; the monthly-average variant needs rostering, P2).
 * Weekly normal hours above the legal maximum are a warning ({@code [verify-legal]}). N-day rotating cycles are Phase
 * 2 (the DDL CHECK allows WEEKLY only).
 *
 * <p>Once a pattern is used by an active schedule assignment, its days, anchor and cycle are frozen (history rests on
 * them): create a new pattern and reassign.
 */
@Service
public class SchedulePatternService {

    /** One day of the cycle: {@code shiftId} or {@code shiftCode} for a WORK day. */
    public record DayInput(int dayIndex, String dayKind, UUID shiftId, String shiftCode) {
    }

    public record PatternInput(String code, String nameVi, String nameEn, Integer cycleLengthDays,
            LocalDate anchorDate, List<DayInput> days) {
    }

    public record DayView(int dayIndex, DayOfWeek dayOfWeek, String dayKind, UUID shiftId, String shiftCode,
            BigDecimal stdHours, BigDecimal dayValue) {
    }

    public record PatternView(UUID id, String code, String nameVi, String nameEn, String patternType,
            int cycleLengthDays, LocalDate anchorDate, List<Integer> weeklyRestDays, BigDecimal stdHoursWeek,
            BigDecimal workDaysWeek, String status, int version, List<DayView> days) {
    }

    public record PatternResult(PatternView pattern, List<String> warnings) {
    }

    private static final Set<Integer> CYCLES = Set.of(7, 14);
    private static final Set<String> KINDS = Set.of("WORK", "REST", "OFF");

    private final ShiftPatternStore store;
    private final AttendanceLegalParameters legal;
    private final BusinessClock clock;

    public SchedulePatternService(ShiftPatternStore store, AttendanceLegalParameters legal, BusinessClock clock) {
        this.store = store;
        this.legal = legal;
        this.clock = clock;
    }

    @AttTransactional(readOnly = true)
    public List<PatternView> list() {
        List<PatternRow> rows = store.patterns();
        Map<UUID, ShiftRow> shifts = store.shifts(rows.stream().flatMap(r -> r.days().stream())
                .map(PatternDay::shiftId).filter(java.util.Objects::nonNull).distinct().toList());
        return rows.stream().map(r -> view(r, shifts)).toList();
    }

    @AttTransactional(readOnly = true)
    public PatternView get(UUID id) {
        return view(load(id));
    }

    @AttTransactional
    public PatternResult create(PatternInput in) {
        Validated v = validate(in, code(in.code(), "pattern"));
        UUID id = store.insertPattern(v.data());
        store.replaceDays(id, v.days());
        return new PatternResult(view(load(id)), warnings(v));
    }

    @AttTransactional
    public PatternResult update(UUID id, PatternInput in, String status, int version) {
        PatternRow current = load(id);
        Validated v = validate(in, current.data().code());
        String st = ScheduleInputs.status(status, current.status());
        boolean layoutChanged = current.data().cycleLengthDays() != v.data().cycleLengthDays()
                || !current.data().anchorDate().equals(v.data().anchorDate()) || !current.days().equals(v.days());
        if (layoutChanged && store.patternAssigned(id)) {
            throw ApiException.conflict("pattern-in-use", "Pattern " + current.data().code()
                    + " is assigned to employees; its days, anchor and cycle are frozen. Create a new pattern instead");
        }
        if ("INACTIVE".equals(st) && "ACTIVE".equals(current.status())) {
            List<String> rules = store.activeRulesUsing(id);
            if (!rules.isEmpty() || store.patternAssigned(id)) {
                throw ApiException.conflict("pattern-in-use", "Pattern " + current.data().code()
                        + " is still assigned (rules " + rules + " or schedule assignments); end them first");
            }
        }
        if (!store.updatePattern(id, v.data(), st, version)) {
            throw stale("schedule pattern");
        }
        if (layoutChanged) {
            store.replaceDays(id, v.days());
        }
        return new PatternResult(view(load(id)), warnings(v));
    }

    // ------------------------------------------------------------------------------------------------ rules

    private record Validated(PatternData data, List<PatternDay> days, List<BigDecimal> weekHours) {
    }

    private Validated validate(PatternInput in, String code) {
        int cycle = in.cycleLengthDays() == null ? 7 : in.cycleLengthDays();
        if (!CYCLES.contains(cycle)) {
            throw invalid("invalid-cycle", "A weekly pattern has 7 days, or 14 for alternating weeks;"
                    + " N-day rotating cycles are not available yet (BR-ATT-011, Phase 2)");
        }
        LocalDate anchor = in.anchorDate();
        if (anchor == null || anchor.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw invalid("invalid-anchor", "The anchor date (day 0 of the cycle) must be a Monday");
        }
        List<DayInput> input = in.days() == null ? List.of() : in.days();
        Map<Integer, DayInput> byIndex = new HashMap<>();
        for (DayInput d : input) {
            if (d.dayIndex() < 0 || d.dayIndex() >= cycle || byIndex.put(d.dayIndex(), d) != null) {
                throw invalid("invalid-days", "Give each day index 0.." + (cycle - 1) + " exactly once");
            }
        }
        if (byIndex.size() != cycle) {
            throw invalid("invalid-days", "Give each day index 0.." + (cycle - 1) + " exactly once");
        }
        Map<String, ShiftRow> shiftsByCode = new HashMap<>();
        Map<UUID, ShiftRow> shiftsById = new HashMap<>();
        for (ShiftRow s : store.shifts()) {
            shiftsByCode.put(s.data().code(), s);
            shiftsById.put(s.id(), s);
        }
        List<PatternDay> days = new ArrayList<>();
        BigDecimal[] weekHours = new BigDecimal[cycle / 7];
        java.util.Arrays.fill(weekHours, BigDecimal.ZERO);
        for (int i = 0; i < cycle; i++) {
            DayInput d = byIndex.get(i);
            String kind = ScheduleInputs.upperOrNull(d.dayKind());
            if (!KINDS.contains(kind)) {
                throw invalid("invalid-day-kind", "Day " + i + ": the kind is WORK, REST or OFF");
            }
            UUID shiftId = null;
            if ("WORK".equals(kind)) {
                ShiftRow s = d.shiftId() != null ? shiftsById.get(d.shiftId())
                        : shiftsByCode.get(ScheduleInputs.upperOrNull(d.shiftCode()));
                if (s == null || !"ACTIVE".equals(s.status())) {
                    throw invalid("unknown-shift", "Day " + i + ": a WORK day needs an active shift");
                }
                shiftId = s.id();
                weekHours[i / 7] = weekHours[i / 7].add(s.data().stdHours());
            } else if (d.shiftId() != null || d.shiftCode() != null) {
                throw invalid("shift-not-allowed", "Day " + i + ": only a WORK day has a shift");
            }
            days.add(new PatternDay(i, kind, shiftId));
        }
        List<Integer> restDays = restDays(days, cycle);
        BigDecimal total = java.util.Arrays.stream(weekHours).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal perWeek = total.divide(BigDecimal.valueOf(cycle / 7), 4, RoundingMode.HALF_UP);
        PatternData data = new PatternData(code, name(in.nameVi()), optionalText(in.nameEn()), cycle, anchor,
                restDays, perWeek);
        return new Validated(data, days, List.of(weekHours));
    }

    /** ISO weekdays that are REST in every week of the cycle; a weekday REST in only some weeks is refused. */
    private static List<Integer> restDays(List<PatternDay> days, int cycle) {
        List<Integer> rest = new ArrayList<>();
        for (int dow = 1; dow <= 7; dow++) {
            int restWeeks = 0;
            for (int w = 0; w < cycle / 7; w++) {
                if ("REST".equals(days.get(w * 7 + dow - 1).dayKind())) {
                    restWeeks++;
                }
            }
            if (restWeeks == cycle / 7) {
                rest.add(dow);
            } else if (restWeeks > 0) {
                throw invalid("alternating-rest-day", DayOfWeek.of(dow) + " is a rest day in some weeks only;"
                        + " the weekly rest day is the same in every week, mark the free day of the other weeks OFF");
            }
        }
        if (rest.isEmpty()) {
            throw invalid("weekly-rest-required", "Every week needs at least one weekly rest day (REST), VN-05");
        }
        return rest;
    }

    private List<String> warnings(Validated v) {
        List<String> out = new ArrayList<>();
        var max = legal.find(AttendanceLegalParameters.MAX_NORMAL_HOURS_WEEK, Scope.tenant(), clock.today());
        if (max.isEmpty()) {
            out.add("legal-parameter-missing:" + AttendanceLegalParameters.MAX_NORMAL_HOURS_WEEK
                    + " (weekly hours not checked)");
            return out;
        }
        for (int w = 0; w < v.weekHours().size(); w++) {
            if (v.weekHours().get(w).compareTo(max.get().asDecimal()) > 0) {
                out.add("normal-hours-above-weekly-limit: week " + (w + 1) + " has " + v.weekHours().get(w)
                        + " h > " + max.get().asDecimal() + " h [verify-legal]");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private PatternRow load(UUID id) {
        return store.pattern(id).orElseThrow(() -> ApiException.notFound("Schedule pattern", id));
    }

    private PatternView view(PatternRow r) {
        return view(r, store.shifts(r.days().stream().map(PatternDay::shiftId).filter(java.util.Objects::nonNull)
                .distinct().toList()));
    }

    private static PatternView view(PatternRow r, Map<UUID, ShiftRow> shifts) {
        PatternData d = r.data();
        BigDecimal workDays = BigDecimal.ZERO;
        List<DayView> days = new ArrayList<>();
        for (PatternDay day : r.days()) {
            ShiftRow s = day.shiftId() == null ? null : shifts.get(day.shiftId());
            if (s != null) {
                workDays = workDays.add(s.data().dayValue());
            }
            days.add(new DayView(day.dayIndex(), d.anchorDate().plusDays(day.dayIndex()).getDayOfWeek(),
                    day.dayKind(), day.shiftId(), s == null ? null : s.data().code(),
                    s == null ? null : s.data().stdHours(), s == null ? null : s.data().dayValue()));
        }
        BigDecimal perWeek = workDays.divide(BigDecimal.valueOf(Math.max(1, d.cycleLengthDays() / 7)), 2,
                RoundingMode.HALF_UP);
        return new PatternView(r.id(), d.code(), d.nameVi(), d.nameEn(), r.patternType(), d.cycleLengthDays(),
                d.anchorDate(), d.weeklyRestDays(), d.stdHoursWeek(), perWeek, r.status(), r.version(), days);
    }
}
