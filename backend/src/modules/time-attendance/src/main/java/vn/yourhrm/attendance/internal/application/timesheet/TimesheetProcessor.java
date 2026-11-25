package vn.yourhrm.attendance.internal.application.timesheet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.attendance.api.HolidayCalendars.Holiday;
import vn.yourhrm.attendance.api.WorkSchedules;
import vn.yourhrm.attendance.api.WorkSchedules.PlannedDay;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.domain.schedule.ShiftLayout;
import vn.yourhrm.attendance.internal.domain.timesheet.DayCalculator;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Code;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.DayInput;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.DayResult;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Leave;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Punch;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Shift;
import vn.yourhrm.attendance.internal.domain.timesheet.PunchAttachment;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.EmploymentDates;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore.PunchRow;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore.ShiftRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CodeRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.CorrectionRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.LeaveDay;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.Period;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore.Placement;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.shared.common.legal.LegalParameters;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The daily processing engine (BR-ATT-030..039, US-ATT-023..030): recomputes the days of an employment from its
 * inputs (planned days and shifts, VALID raw punches, approved corrections and leave, the policy setting, legal
 * parameters and protected-worker flags as of each date) and writes {@code att.daily_timesheet}.
 * <ul>
 *   <li><b>Idempotent and deterministic</b> (BR-ATT-038, NFR-ATT-006): the inputs of each day are hashed; an
 *       unchanged hash writes nothing, a changed one bumps {@code calc_version} and stamps the legal pack and rule
 *       version.</li>
 *   <li><b>Never touches a CLOSED or LOCKED period</b> (invariant 2): such days are counted as frozen and left as
 *       they are. A CLOSED period is corrected by reopen and re-close. For a LOCKED day the inputs are still hashed:
 *       when they differ from the stored day, the {@link LockedDayChangeSink}s record a retro request, and the next
 *       close attaches the difference (computed with {@link #preview}) as retro lines (BR-ATT-071).</li>
 *   <li>Future days are not computed (the range is capped at today). Days outside the employment are written as
 *       NOT_EMPLOYED only where an attendance period or an earlier row exists (BR-ATT-039).</li>
 *   <li>Each day is written in a savepoint: a day that fails (e.g. a month without a timesheet partition) is reported
 *       and the others are kept.</li>
 * </ul>
 */
@Service
public class TimesheetProcessor {

    private static final Logger log = LoggerFactory.getLogger(TimesheetProcessor.class);
    static final int CHUNK_DAYS = 62;
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Outcome of a recalculation. */
    public record Summary(int written, int unchanged, int frozen, int skipped, List<String> failures) {

        static Summary empty() {
            return new Summary(0, 0, 0, 0, List.of());
        }

        Summary plus(Summary o) {
            List<String> f = new ArrayList<>(failures);
            f.addAll(o.failures);
            return new Summary(written + o.written, unchanged + o.unchanged, frozen + o.frozen, skipped + o.skipped,
                    f);
        }
    }

    private final WorkSchedules schedules;
    private final ShiftPatternStore shifts;
    private final PunchStore punches;
    private final TimesheetStore store;
    private final Settings settings;
    private final AttendanceLegalParameters legal;
    private final BusinessClock clock;
    private final ObjectProvider<ActualOvertimeSink> otSinks;
    private final ObjectProvider<LockedDayChangeSink> lockedSinks;
    private final TransactionTemplate newTx;
    private final TransactionTemplate savepoint;

    /**
     * A day computed in memory, not written ({@link #preview}): the result, where it belongs and the shift group of
     * its planned shift.
     */
    public record Preview(LocalDate date, DayResult result, Placement placement, String shiftGroupCode) {
    }

    public TimesheetProcessor(WorkSchedules schedules, ShiftPatternStore shifts, PunchStore punches,
            TimesheetStore store, Settings settings, AttendanceLegalParameters legal, BusinessClock clock,
            ObjectProvider<ActualOvertimeSink> otSinks, ObjectProvider<LockedDayChangeSink> lockedSinks,
            @Qualifier(AttDb.TX) PlatformTransactionManager attTm) {
        this.schedules = schedules;
        this.shifts = shifts;
        this.punches = punches;
        this.store = store;
        this.settings = settings;
        this.legal = legal;
        this.clock = clock;
        this.otSinks = otSinks;
        this.lockedSinks = lockedSinks;
        this.newTx = new TransactionTemplate(attTm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.savepoint = new TransactionTemplate(attTm);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** Recomputes {@code [from, to]} of the employment in a new T&amp;A transaction per chunk (event triggers, jobs). */
    public Summary recompute(UUID employmentId, LocalDate from, LocalDate to) {
        Summary total = Summary.empty();
        for (LocalDate[] chunk : chunks(from, to)) {
            Summary s = newTx.execute(st -> compute(employmentId, chunk[0], chunk[1], null));
            total = total.plus(s);
        }
        return total;
    }

    /** Recomputes inside the caller's T&amp;A transaction (sees its uncommitted corrections). */
    @AttTransactional
    public Summary recomputeInTx(UUID employmentId, LocalDate from, LocalDate to) {
        Summary total = Summary.empty();
        for (LocalDate[] chunk : chunks(from, to)) {
            total = total.plus(compute(employmentId, chunk[0], chunk[1], null));
        }
        return total;
    }

    /**
     * Computes the days of {@code [from, to]} (capped at today) in memory with today's inputs, frozen periods included,
     * and writes nothing: the retro of a LOCKED period compares these with what was handed over (BR-ATT-071). Days
     * that would be skipped (not employed, no period, no earlier row) are absent.
     */
    @AttTransactional(readOnly = true)
    public List<Preview> preview(UUID employmentId, LocalDate from, LocalDate to) {
        Map<LocalDate, Preview> out = new java.util.TreeMap<>();
        for (LocalDate[] chunk : chunks(from, to)) {
            compute(employmentId, chunk[0], chunk[1], out);
        }
        return List.copyOf(out.values());
    }

    private List<LocalDate[]> chunks(LocalDate from, LocalDate to) {
        LocalDate end = to.isAfter(clock.today()) ? clock.today() : to;
        List<LocalDate[]> out = new ArrayList<>();
        for (LocalDate s = from; !s.isAfter(end); s = s.plusDays(CHUNK_DAYS)) {
            LocalDate e = s.plusDays(CHUNK_DAYS - 1);
            out.add(new LocalDate[] {s, e.isAfter(end) ? end : e});
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ engine

    /** {@code preview} non-null: compute every day in memory into it, write nothing. */
    private Summary compute(UUID employmentId, LocalDate from, LocalDate to, Map<LocalDate, Preview> preview) {
        Optional<EmploymentDates> emp = punches.employment(employmentId);
        if (emp.isEmpty()) {
            return new Summary(0, 0, 0, (int) (to.toEpochDay() - from.toEpochDay() + 1), List.of());
        }
        List<PlannedDay> plan = schedules.plan(employmentId, from.minusDays(1), to.plusDays(1));
        Map<LocalDate, PlannedDay> planByDate = new HashMap<>();
        plan.forEach(p -> planByDate.put(p.date(), p));
        Map<UUID, ShiftRow> shiftRows = shifts.shifts(plan.stream().map(PlannedDay::shiftId)
                .filter(java.util.Objects::nonNull).distinct().toList());
        Map<LocalDate, Shift> shiftByDate = new HashMap<>();
        for (PlannedDay p : plan) {
            if (p.scheduled() && p.shiftId() != null && shiftRows.containsKey(p.shiftId())) {
                shiftByDate.put(p.date(), shift(shiftRows.get(p.shiftId())));
            }
        }
        ZoneId zone = punches.placement(employmentId, to)
                .flatMap(pl -> store.locationZone(pl.locationId(), to)).map(z -> {
                    try {
                        return ZoneId.of(z);
                    } catch (RuntimeException e) {
                        return null;
                    }
                }).orElse(DEFAULT_ZONE);

        List<Punch> raw = new ArrayList<>();
        for (PunchRow r : punches.punchesOfEmployment(employmentId, from.minusDays(1), to.plusDays(1))) {
            if ("VALID".equals(r.validationStatus())) {
                raw.add(new Punch(r.id(), r.punchAt(), r.direction(), "RAW"));
            }
        }
        PunchAttachment.Result attached = PunchAttachment.attach(raw, shiftByDate, zone);
        Map<LocalDate, List<CorrectionRow>> corrections = new HashMap<>();
        store.corrections(employmentId, from, to, true)
                .forEach(c -> corrections.computeIfAbsent(c.workDate(), k -> new ArrayList<>()).add(c));
        Map<LocalDate, List<LeaveDay>> leaves = new HashMap<>();
        store.approvedLeave(employmentId, from, to)
                .forEach(l -> leaves.computeIfAbsent(l.date(), k -> new ArrayList<>()).add(l));
        Map<String, CodeRow> codes = store.effectiveCodes();
        Map<String, String> pairing = new HashMap<>();

        int written = 0;
        int unchanged = 0;
        int frozen = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            LocalDate day = d;
            PlannedDay p = planByDate.get(d);
            var pl = punches.placement(employmentId, d);
            UUID le = pl.map(PunchStore.Placement::legalEntityId).orElse(emp.get().legalEntityId());
            UUID pg = store.payGroup(employmentId, d).orElse(null);
            Optional<Period> period = store.periodOn(le, pg, d);
            boolean locked = period.isPresent() && "LOCKED".equals(period.get().status());
            if (preview == null && period.isPresent() && "CLOSED".equals(period.get().status())) {
                frozen++;
                continue;
            }
            boolean employed = emp.get().employedOn(d);
            if (!employed && period.isEmpty() && store.day(employmentId, d).isEmpty()) {
                skipped++;
                continue;
            }
            Shift shift = p != null && p.scheduled() ? shiftByDate.get(d) : null;
            String dayType = dayType(p);
            boolean paidHoliday = p != null && (p.substituteForDate() != null
                    || (p.holiday() != null && p.holiday().paid()));

            // corrections: synthetic punches, full-day code, manual flag
            List<Punch> dayPunches = new ArrayList<>(attached.byDate().getOrDefault(d, List.of()));
            Code override = null;
            boolean manual = false;
            for (CorrectionRow c : corrections.getOrDefault(d, List.of())) {
                manual |= "ADMIN".equals(c.source()) || "BULK".equals(c.source()) || "IMPORT".equals(c.source());
                if (c.requestedCode() != null) {
                    override = code(codes, c.requestedCode(), "WORK");
                }
                boolean replace = c.requestedInAt() != null && c.requestedOutAt() != null
                        && ("WRONG_TIME".equals(c.correctionType()) || "ADMIN_EDIT".equals(c.correctionType()));
                if (replace) {
                    dayPunches.clear();
                }
                if (c.requestedInAt() != null) {
                    dayPunches.add(new Punch(c.id(), c.requestedInAt(), "IN", "CORRECTION"));
                }
                if (c.requestedOutAt() != null) {
                    dayPunches.add(new Punch(c.id(), c.requestedOutAt(), "OUT", "CORRECTION"));
                }
            }
            List<Leave> dayLeaves = leaves.getOrDefault(d, List.of()).stream()
                    .map(l -> new Leave(code(codes, l.code(), "LEAVE"), l.part())).toList();
            String mode = pairing.computeIfAbsent(le + "|" + d, k -> pairingMode(le, day));
            UUID loc = pl.map(PunchStore.Placement::locationId).orElse(null);
            LocalTime[] night = new LocalTime[2];
            UUID pack = nightWindow(shift == null ? null : shiftRows.get(p.shiftId()), le, loc, d, night);
            int protectedMin = protectedMinutes(employmentId, le, loc, d);
            DayInput input = new DayInput(d, zone, employed ? "EMPLOYED" : "NOT_EMPLOYED", dayType, paidHoliday,
                    shift, dayPunches, dayLeaves, override, manual, mode, night[0], night[1], protectedMin);
            DayResult result = DayCalculator.compute(input);
            Placement placement = new Placement(p == null ? null : p.assignmentKey(), le,
                    pl.map(PunchStore.Placement::orgUnitId).orElse(null), loc, pg,
                    period.map(Period::id).orElse(null), shift == null ? null : shift.id());
            String hash = sha256(input + "|" + placement + "|" + pack + "|" + DayModel.RULE_VERSION);
            if (preview != null) {
                preview.put(d, new Preview(d, result, placement,
                        shift == null ? null : shiftRows.get(p.shiftId()).data().shiftGroupCode()));
                continue;
            }
            if (locked) {
                // never rewritten (invariant 2); a change of its inputs becomes a retro delta (BR-ATT-071)
                frozen++;
                boolean changed = store.day(employmentId, d).map(r -> !hash.equals(r.calcInputsHash()))
                        .orElse(true);
                if (changed) {
                    UUID periodId = period.get().id();
                    lockedSinks.orderedStream().forEach(s -> s.lockedDayChanged(employmentId, periodId, day));
                }
                continue;
            }
            try {
                Optional<Integer> version = savepoint.execute(st -> {
                    Optional<Integer> v = store.upsertDay(employmentId, day, placement, result, hash, pack,
                            DayModel.RULE_VERSION);
                    if (v.isPresent()) {
                        pushOvertime(employmentId, day, result);
                    }
                    return v;
                });
                if (version.isPresent()) {
                    written++;
                } else {
                    unchanged++;
                }
            } catch (RuntimeException ex) {
                String why = String.valueOf(ex.getMessage());
                failures.add(d + ": " + (why.contains("no partition") ? "no timesheet partition for the month"
                        : ex.getClass().getSimpleName()));
                log.warn("Daily processing of {} on {} failed: {}", employmentId, d, ex.toString());
            }
        }
        return new Summary(written, unchanged, frozen, skipped, failures);
    }

    private void pushOvertime(UUID employmentId, LocalDate day, DayResult r) {
        List<ActualOvertimeSink.Interval> spans = r.otSpans().stream()
                .map(s -> new ActualOvertimeSink.Interval(s.start(), s.end())).toList();
        otSinks.orderedStream().forEach(sink -> sink.recordActual(employmentId, day, spans));
    }

    // ------------------------------------------------------------------------------------------------ inputs

    static String dayType(PlannedDay p) {
        if (p == null || !p.scheduled()) {
            return "REST_DAY";
        }
        if (p.substituteForDate() != null) {
            return "SUBSTITUTE_HOLIDAY";
        }
        Holiday h = p.holiday();
        if (h != null && !h.foreignersOnly()) {
            return switch (h.dayType()) {
                case "PUBLIC" -> "PUBLIC_HOLIDAY";
                case "SUBSTITUTE" -> "SUBSTITUTE_HOLIDAY";
                case "COMPANY" -> "COMPANY_HOLIDAY";
                default -> "WORK".equals(p.dayKind()) ? "WORKDAY" : "REST_DAY";
            };
        }
        return "WORK".equals(p.dayKind()) ? "WORKDAY" : "REST_DAY";
    }

    static Shift shift(ShiftRow r) {
        var d = r.data();
        List<ShiftLayout.Break> breaks = r.breaks().stream()
                .map(b -> new ShiftLayout.Break(b.start(), b.end(), b.durationMin(), b.paid(), b.flexible())).toList();
        return new Shift(r.id(), new ShiftLayout(d.startTime(), d.endTime(), breaks), d.graceInMin(), d.graceOutMin(),
                d.roundingRuleCode(), d.punchWindowBeforeMin(), d.punchWindowAfterMin(), d.minMinutesHalfDay(),
                d.minMinutesFullDay(), d.stdHours(), d.dayValue(), d.shiftGroupCode());
    }

    private static Code code(Map<String, CodeRow> codes, String code, String fallbackKind) {
        CodeRow r = codes.get(code);
        return r == null ? new Code(code, fallbackKind, false) : new Code(r.code(), r.kind(), r.countsAsWorked());
    }

    private String pairingMode(UUID le, LocalDate d) {
        try {
            String m = settings.stringValue(TimesheetCatalogues.SETTING_PAIRING_MODE, new Settings.Scope(le, null), d);
            return m != null && DayModel.PAIRING_MODES.contains(m) ? m : "FIRST_LAST";
        } catch (RuntimeException e) {
            return "FIRST_LAST";
        }
    }

    /** Fills {@code out} with the night window (shift override, else the legal pack); returns the pack id used. */
    private UUID nightWindow(ShiftRow shift, UUID le, UUID loc, LocalDate d, LocalTime[] out) {
        if (shift != null && shift.data().nightWindowStart() != null) {
            out[0] = shift.data().nightWindowStart();
            out[1] = shift.data().nightWindowEnd();
            return null;
        }
        LegalParameters.Scope scope = new LegalParameters.Scope(le, loc);
        var start = legal.find(AttendanceLegalParameters.NIGHT_START, scope, d);
        var end = legal.find(AttendanceLegalParameters.NIGHT_END, scope, d);
        if (start.isEmpty() || end.isEmpty()) {
            return null;
        }
        try {
            out[0] = LocalTime.parse(start.get().asString());
            out[1] = LocalTime.parse(end.get().asString());
        } catch (RuntimeException e) {
            out[0] = null;
            out[1] = null;
            return null;
        }
        return start.get().packId();
    }

    /** BR-ATT-035: the statutory paid reduction minutes as of the work date (the larger one applies). */
    private int protectedMinutes(UUID employmentId, UUID le, UUID loc, LocalDate d) {
        boolean[] f = store.protectedFlags(employmentId, d);
        int min = 0;
        LegalParameters.Scope scope = new LegalParameters.Scope(le, loc);
        if (f[0]) {
            min = Math.max(min, legal.find(AttendanceLegalParameters.PROTECTED_BREAK_PREGNANCY_MIN, scope, d)
                    .map(LegalParameters.Value::asInt).orElse(0));
        }
        if (f[1]) {
            min = Math.max(min, legal.find(AttendanceLegalParameters.PROTECTED_BREAK_CHILD_MIN, scope, d)
                    .map(LegalParameters.Value::asInt).orElse(0));
        }
        return min;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Employments of a legal entity with a line in {@code [from, to]} (bulk recalculation). */
    @AttTransactional(readOnly = true)
    public List<UUID> employmentsOf(UUID legalEntityId, LocalDate from, LocalDate to) {
        return store.employmentsOfLegalEntity(legalEntityId, from, to);
    }

    /** Recomputes several employments, each in its own transaction. */
    public Summary recomputeAll(Collection<UUID> employmentIds, LocalDate from, LocalDate to) {
        Summary total = Summary.empty();
        for (UUID e : employmentIds) {
            try {
                total = total.plus(recompute(e, from, to));
            } catch (RuntimeException ex) {
                log.warn("Daily processing of {} failed: {}", e, ex.toString());
                total = total.plus(new Summary(0, 0, 0, 0, List.of(e + ": " + ex.getClass().getSimpleName())));
            }
        }
        return total;
    }
}
