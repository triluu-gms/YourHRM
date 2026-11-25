package vn.yourhrm.attendance.internal.application.schedule;

import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.code;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.invalid;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.name;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.optionalText;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.stale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.domain.schedule.ShiftLayout;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.BreakData;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.ShiftData;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.ShiftRow;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Shift definitions (BR-ATT-010, US-ATT-008): one block of work from a local start time to a local end time on the
 * work date (overnight when the end is not after the start), fixed or flexible breaks (paid breaks count as working
 * time, VN-03), grace minutes, rounding rule code, punch windows, minimum minutes for a half / full day, standard hours
 * credited and day value, and an optional night-window override (NULL = the legal window, VN-02).
 *
 * <p>Statutory checks (minimum break, maximum normal daily hours) use legal parameters and only warn: the shift may be
 * legitimate under a weekly-hours regime, and every value is {@code [verify-legal]}.
 *
 * <p>Once a shift is used by a pattern that an active schedule or rule uses, its time layout (times, breaks, standard
 * hours, day value, night window) is frozen, because daily results already rest on it: create a new shift instead.
 * Names, grace, rounding, windows and the group stay editable. Core hours (flex, BR-ATT-012) and split shifts
 * (BR-ATT-013) are Phase 2.
 */
@Service
public class ShiftService {

    public record BreakInput(LocalTime start, LocalTime end, Integer durationMin, boolean paid, boolean flexible) {
    }

    public record ShiftInput(String code, String nameVi, String nameEn, LocalTime startTime, LocalTime endTime,
            Integer graceInMin, Integer graceOutMin, String roundingRuleCode, Integer punchWindowBeforeMin,
            Integer punchWindowAfterMin, Integer minMinutesHalfDay, Integer minMinutesFullDay, BigDecimal stdHours,
            BigDecimal dayValue, LocalTime nightWindowStart, LocalTime nightWindowEnd, String shiftGroupCode,
            List<BreakInput> breaks) {
    }

    public record ShiftView(UUID id, String code, String nameVi, String nameEn, LocalTime startTime,
            LocalTime endTime, boolean crossesMidnight, int spanMinutes, int workingMinutes, int graceInMin,
            int graceOutMin, String roundingRuleCode, int punchWindowBeforeMin, int punchWindowAfterMin,
            Integer minMinutesHalfDay, Integer minMinutesFullDay, BigDecimal stdHours, BigDecimal dayValue,
            LocalTime nightWindowStart, LocalTime nightWindowEnd, String shiftGroupCode, List<BreakData> breaks,
            String status, int version) {
    }

    /** A saved shift plus the statutory warnings of its layout. */
    public record ShiftResult(ShiftView shift, List<String> warnings) {
    }

    private final ShiftPatternStore store;
    private final AttendanceLegalParameters legal;
    private final BusinessClock clock;

    public ShiftService(ShiftPatternStore store, AttendanceLegalParameters legal, BusinessClock clock) {
        this.store = store;
        this.legal = legal;
        this.clock = clock;
    }

    @AttTransactional(readOnly = true)
    public List<ShiftView> list() {
        return store.shifts().stream().map(ShiftService::view).toList();
    }

    @AttTransactional(readOnly = true)
    public ShiftView get(UUID id) {
        return view(load(id));
    }

    @AttTransactional
    public ShiftResult create(ShiftInput in) {
        Validated v = validate(in, code(in.code(), "shift"));
        UUID id = store.insertShift(v.data(), v.layout().crossesMidnight());
        store.replaceBreaks(id, v.breaks());
        return new ShiftResult(view(load(id)), warnings(v));
    }

    /** Replaces the shift (the code never changes); {@code status} ACTIVE / INACTIVE, null keeps it. */
    @AttTransactional
    public ShiftResult update(UUID id, ShiftInput in, String status, int version) {
        ShiftRow current = load(id);
        Validated v = validate(in, current.data().code());
        String st = ScheduleInputs.status(status, current.status());
        if (store.shiftInUse(id) && layoutChanged(current, v)) {
            throw ApiException.conflict("shift-in-use", "Shift " + current.data().code()
                    + " is used by an active schedule; its times, breaks, standard hours and night window are frozen."
                    + " Create a new shift instead");
        }
        if ("INACTIVE".equals(st) && "ACTIVE".equals(current.status())) {
            List<String> patterns = store.activePatternsUsing(id);
            if (!patterns.isEmpty()) {
                throw ApiException.conflict("shift-in-use", "Shift " + current.data().code()
                        + " is used by active schedule pattern(s) " + patterns + "; deactivate or change them first");
            }
        }
        if (!store.updateShift(id, v.data(), v.layout().crossesMidnight(), st, version)) {
            throw stale("shift");
        }
        if (!current.breaks().equals(v.breaks())) {
            store.replaceBreaks(id, v.breaks());
        }
        return new ShiftResult(view(load(id)), warnings(v));
    }

    // ------------------------------------------------------------------------------------------------ rules

    private record Validated(ShiftData data, ShiftLayout layout, List<BreakData> breaks) {
    }

    private static Validated validate(ShiftInput in, String code) {
        if (in.startTime() == null || in.endTime() == null) {
            throw invalid("shift-times-required", "The start and end times are required");
        }
        List<BreakData> breaks = new ArrayList<>();
        List<ShiftLayout.Break> layoutBreaks = new ArrayList<>();
        int seq = 1;
        for (BreakInput b : in.breaks() == null ? List.<BreakInput>of() : in.breaks()) {
            int duration = b.durationMin() != null ? b.durationMin()
                    : b.start() != null && b.end() != null
                            ? Math.floorMod((b.end().toSecondOfDay() - b.start().toSecondOfDay()) / 60, 1440) : 0;
            breaks.add(new BreakData(seq++, b.flexible() ? null : b.start(), b.flexible() ? null : b.end(), duration,
                    b.paid(), b.flexible()));
            layoutBreaks.add(new ShiftLayout.Break(b.start(), b.end(), duration, b.paid(), b.flexible()));
        }
        ShiftLayout layout = new ShiftLayout(in.startTime(), in.endTime(), layoutBreaks);
        List<String> problems = layout.breakProblems();
        if (!problems.isEmpty()) {
            throw invalid(problems.getFirst(), "The breaks do not fit the shift: " + problems);
        }
        int working = layout.workingMinutes();
        int graceIn = minutes(in.graceInMin(), 0, 240, "grace-in");
        int graceOut = minutes(in.graceOutMin(), 0, 240, "grace-out");
        int before = minutes(in.punchWindowBeforeMin(), 120, 720, "punch-window-before");
        int after = minutes(in.punchWindowAfterMin(), 240, 720, "punch-window-after");
        Integer half = in.minMinutesHalfDay();
        Integer full = in.minMinutesFullDay();
        if ((half != null && (half < 0 || half > working)) || (full != null && (full < 0 || full > working))) {
            throw invalid("invalid-minimum-minutes", "Minimum minutes for a half / full day lie between 0 and the "
                    + working + " working minutes of the shift");
        }
        if (half != null && full != null && half > full) {
            throw invalid("invalid-minimum-minutes", "The half-day minimum cannot exceed the full-day minimum");
        }
        BigDecimal maxHours = BigDecimal.valueOf(working).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        BigDecimal stdHours = in.stdHours() == null ? maxHours.setScale(2, RoundingMode.HALF_UP) : in.stdHours();
        if (stdHours.signum() < 0 || stdHours.compareTo(maxHours) > 0) {
            throw invalid("invalid-std-hours", "Standard hours lie between 0 and the " + maxHours
                    + " working hours of the shift (span minus unpaid breaks)");
        }
        BigDecimal dayValue = in.dayValue() == null ? BigDecimal.ONE : in.dayValue();
        if (dayValue.signum() < 0 || dayValue.compareTo(BigDecimal.ONE) > 0) {
            throw invalid("invalid-day-value", "The day value lies between 0 and 1 (e.g. 0.5 for a half-day shift)");
        }
        if ((in.nightWindowStart() == null) != (in.nightWindowEnd() == null)) {
            throw invalid("invalid-night-window", "Give both ends of the night-window override, or neither");
        }
        ShiftData data = new ShiftData(code, name(in.nameVi()), optionalText(in.nameEn()), in.startTime(),
                in.endTime(), graceIn, graceOut, ScheduleInputs.upperOrNull(in.roundingRuleCode()), before, after,
                half, full, stdHours, dayValue, in.nightWindowStart(), in.nightWindowEnd(),
                ScheduleInputs.upperOrNull(in.shiftGroupCode()));
        return new Validated(data, layout, breaks);
    }

    private static int minutes(Integer value, int dflt, int max, String what) {
        int v = value == null ? dflt : value;
        if (v < 0 || v > max) {
            throw invalid("invalid-" + what, "The " + what.replace('-', ' ') + " minutes lie between 0 and " + max);
        }
        return v;
    }

    private static boolean layoutChanged(ShiftRow current, Validated v) {
        ShiftData a = current.data();
        ShiftData b = v.data();
        return !a.startTime().equals(b.startTime()) || !a.endTime().equals(b.endTime())
                || a.stdHours().compareTo(b.stdHours()) != 0 || a.dayValue().compareTo(b.dayValue()) != 0
                || !Objects.equals(a.nightWindowStart(), b.nightWindowStart())
                || !Objects.equals(a.nightWindowEnd(), b.nightWindowEnd()) || !current.breaks().equals(v.breaks());
    }

    /** VN-01 / VN-03 checks against legal parameters ({@code [verify-legal]}); a missing parameter is a warning too. */
    private List<String> warnings(Validated v) {
        List<String> out = new ArrayList<>();
        var today = clock.today();
        ShiftLayout layout = v.layout();
        var dueAfter = legal.find(AttendanceLegalParameters.BREAK_DUE_AFTER_MIN, Scope.tenant(), today);
        String minKey = layout.crossesMidnight() ? AttendanceLegalParameters.BREAK_MIN_NIGHT
                : AttendanceLegalParameters.BREAK_MIN_DAY;
        var minBreak = legal.find(minKey, Scope.tenant(), today);
        if (dueAfter.isEmpty() || minBreak.isEmpty()) {
            out.add("legal-parameter-missing:" + (dueAfter.isEmpty() ? AttendanceLegalParameters.BREAK_DUE_AFTER_MIN
                    : minKey) + " (break not checked)");
        } else if (layout.spanMinutes() >= dueAfter.get().asInt()
                && layout.breakMinutes() < minBreak.get().asInt()) {
            out.add("break-below-legal-minimum: " + layout.breakMinutes() + " min of break for a "
                    + layout.spanMinutes() + "-minute shift; the legal minimum is " + minBreak.get().asInt()
                    + " min [verify-legal]");
        }
        var maxDay = legal.find(AttendanceLegalParameters.MAX_NORMAL_HOURS_DAY, Scope.tenant(), today);
        if (maxDay.isEmpty()) {
            out.add("legal-parameter-missing:" + AttendanceLegalParameters.MAX_NORMAL_HOURS_DAY
                    + " (daily hours not checked)");
        } else if (v.data().stdHours().compareTo(maxDay.get().asDecimal()) > 0) {
            out.add("normal-hours-above-daily-limit: " + v.data().stdHours() + " h > " + maxDay.get().asDecimal()
                    + " h; only allowed when normal hours are counted per week [verify-legal]");
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ShiftRow load(UUID id) {
        return store.shift(id).orElseThrow(() -> ApiException.notFound("Shift", id));
    }

    static ShiftView view(ShiftRow r) {
        ShiftData d = r.data();
        ShiftLayout layout = new ShiftLayout(d.startTime(), d.endTime(), r.breaks().stream()
                .map(b -> new ShiftLayout.Break(b.start(), b.end(), b.durationMin(), b.paid(), b.flexible()))
                .toList());
        return new ShiftView(r.id(), d.code(), d.nameVi(), d.nameEn(), d.startTime(), d.endTime(),
                r.crossesMidnight(), layout.spanMinutes(), layout.workingMinutes(), d.graceInMin(), d.graceOutMin(),
                d.roundingRuleCode(), d.punchWindowBeforeMin(), d.punchWindowAfterMin(), d.minMinutesHalfDay(),
                d.minMinutesFullDay(), d.stdHours(), d.dayValue(), d.nightWindowStart(), d.nightWindowEnd(),
                d.shiftGroupCode(), r.breaks(), r.status(), r.version());
    }
}
