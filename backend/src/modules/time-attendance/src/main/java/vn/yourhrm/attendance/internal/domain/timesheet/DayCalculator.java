package vn.yourhrm.attendance.internal.domain.timesheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.yourhrm.attendance.internal.domain.schedule.ShiftLayout;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Code;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.CodeQty;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.DayInput;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.DayResult;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Leave;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Punch;
import vn.yourhrm.attendance.internal.domain.timesheet.DayModel.Shift;
import vn.yourhrm.attendance.internal.domain.timesheet.Intervals.Span;

/**
 * The daily timesheet of one employment and work date (BR-ATT-031..035, 039; US-ATT-024..029). Pure and
 * deterministic.
 *
 * <p><b>Day status and codes</b> in order of precedence (US-ATT-027): not employed (NE) / suspended (TH) → a full-day
 * code of an approved correction or admin edit (e.g. CT, WFH; a worked code applies on a working day only) → holiday or substitute day (L / NB, when paid) → rest
 * day (no code) → approved leave (full or half day) → the punches: X for the worked part, V for the uncovered part of
 * the working day. A half day is two codes of 0.5.
 *
 * <p><b>Quantities</b> on a working day: worked = presence inside the shift minus unpaid breaks (fixed breaks where
 * they lie, flexible breaks by duration), rounded by the shift's rule ({@code ROUND_DOWN|UP|NEAREST_<n>}); paid breaks
 * count as worked (VN-03). Late / early beyond the grace minutes (one fixed rule; the policy matrix BR-ATT-034 is P2).
 * The statutory short paid breaks (BR-ATT-035) are credited as working time up to the shortfall, reducing the early
 * leave first, then the late arrival. Night hours = normal work inside the night window (NW); presence outside the
 * shift, or any presence on a rest day / holiday, is an OT candidate (classified by the OT stream, BRD §4.5). Day value
 * = the shift's value when worked ≥ the full-day minimum (default: working minutes minus grace), half of it when ≥ the
 * half-day minimum (default: half of the full-day minimum), else 0.
 */
public final class DayCalculator {

    private DayCalculator() {
    }

    public static final String WORK_CODE = "X";
    public static final String ABSENT_CODE = "V";
    public static final String HOLIDAY_CODE = "L";
    public static final String SUBSTITUTE_CODE = "NB";
    public static final String NOT_EMPLOYED_CODE = "NE";
    public static final String SUSPENDED_CODE = "TH";
    /** Non-blocking exception: a worked code (X / CT / WFH) was set on a rest day or holiday and not applied. */
    public static final String CODE_NOT_APPLIED = "CODE_NOT_APPLIED_OFF_DAY";
    /** Code kinds that stand for a worked working day; they apply only on a scheduled working day. */
    public static final Set<String> WORKED_OVERRIDE_KINDS = Set.of("WORK", "TRIP", "WFH");

    private static final Pattern ROUNDING = Pattern.compile("^ROUND_(DOWN|UP|NEAREST)_(\\d{1,3})$");

    public static DayResult compute(DayInput in) {
        String dayType = in.dayType();
        if ("NOT_EMPLOYED".equals(in.employment()) || "SUSPENDED".equals(in.employment())) {
            boolean ne = "NOT_EMPLOYED".equals(in.employment());
            return empty(dayType, in.employment(), ne ? NOT_EMPLOYED_CODE : SUSPENDED_CODE, BigDecimal.ONE,
                    List.of(), in.manualOverride());
        }
        Shift shift = in.shift();
        boolean workday = "WORKDAY".equals(dayType) && shift != null;
        BigDecimal dv = shift == null ? BigDecimal.ONE : shift.dayValue();
        List<String> exceptions = new ArrayList<>();

        // pairing (BR-ATT-031)
        List<Punch> punches = new ArrayList<>(in.punches());
        punches.sort(Comparator.comparing(Punch::at).thenComparing(p -> String.valueOf(p.id())));
        List<Span> presence = pair(punches, in.pairingMode(), in, exceptions);
        Instant firstIn = presence.isEmpty() ? null : instant(in, presence.getFirst().start());
        Instant lastOut = presence.isEmpty() ? null : instant(in, presence.getLast().end());
        long presenceMin = Intervals.length(presence);

        // the shift on the timeline
        List<Span> shiftSpan = List.of();
        List<Span> unpaidFixed = List.of();
        List<Span> paidFixed = List.of();
        int flexUnpaid = 0;
        int flexPaid = 0;
        long s = 0;
        long e = 0;
        if (shift != null) {
            long[] span = PunchAttachment.shiftSpan(shift);
            s = span[0];
            e = span[1];
            shiftSpan = List.of(new Span(s, e));
            List<Span> unpaid = new ArrayList<>();
            List<Span> paid = new ArrayList<>();
            for (ShiftLayout.Break b : shift.layout().breaks()) {
                if (b.flexible() || b.start() == null) {
                    if (b.paid()) {
                        flexPaid += b.durationMin();
                    } else {
                        flexUnpaid += b.durationMin();
                    }
                    continue;
                }
                long bs = s + Math.floorMod((b.start().toSecondOfDay() - shift.layout().start().toSecondOfDay()) / 60,
                        1440);
                (b.paid() ? paid : unpaid).add(new Span(bs, bs + b.durationMin()));
            }
            unpaidFixed = unpaid;
            paidFixed = paid;
        }
        List<Span> inShift = Intervals.intersect(presence, shiftSpan);
        List<Span> work = Intervals.subtract(inShift, unpaidFixed);
        List<Span> outside = Intervals.subtract(presence, shiftSpan);
        long otCandidate = Intervals.length(outside);

        // full-day code of a correction / admin edit. A worked code (X, CT, WFH) means a working day was worked
        // elsewhere: on a rest day or holiday it does not apply (the paid holiday stays, presence stays an OT
        // candidate) and the day is flagged so the admin sees the code was set and ignored (BR-ATT-070 P1).
        boolean workedOverrideOffDay = in.codeOverride() != null && !workday
                && WORKED_OVERRIDE_KINDS.contains(in.codeOverride().kind());
        if (workedOverrideOffDay) {
            exceptions.add(CODE_NOT_APPLIED);
        }
        if (in.codeOverride() != null && !workedOverrideOffDay) {
            Code c = in.codeOverride();
            String status = switch (c.kind()) {
                case "LEAVE" -> "ON_LEAVE";
                case "HOLIDAY" -> "HOLIDAY";
                case "ABSENCE" -> "ABSENT";
                case "REST" -> "REST";
                case "NOT_EMPLOYED" -> "NOT_EMPLOYED";
                case "SUSPENDED" -> "SUSPENDED";
                default -> "PRESENT";
            };
            BigDecimal value = c.countsAsWorked() && workday ? dv : BigDecimal.ZERO;
            return new DayResult(dayType, status, c.code(), List.of(new CodeQty(c.code(), dv)), firstIn, lastOut,
                    workday ? shift.stdHours() : BigDecimal.ZERO, hours(Intervals.length(work)), BigDecimal.ZERO, 0,
                    0, 0, BigDecimal.ZERO, BigDecimal.ZERO, (int) Math.max(0, otCandidate), value, exceptions,
                    false, in.manualOverride(), spans(in, outside));
        }

        // holidays and rest days: presence is OT candidate (OT-PH / OT-RD), no normal work
        if (!"WORKDAY".equals(dayType) || shift == null) {
            List<CodeQty> codes = new ArrayList<>();
            String status = "REST";
            if (dayType.endsWith("HOLIDAY")) {
                status = "HOLIDAY";
                if (in.paidHoliday()) {
                    codes.add(new CodeQty("SUBSTITUTE_HOLIDAY".equals(dayType) ? SUBSTITUTE_CODE : HOLIDAY_CODE,
                            BigDecimal.ONE));
                }
            }
            return new DayResult(dayType, status, codes.isEmpty() ? null : codes.getFirst().code(), codes, firstIn,
                    lastOut, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, BigDecimal.ZERO,
                    BigDecimal.ZERO, (int) presenceMin, BigDecimal.ZERO, exceptions, false, in.manualOverride(),
                    spans(in, presence));
        }

        // working day
        int workingMin = shift.layout().workingMinutes();
        BigDecimal leaveQty = BigDecimal.ZERO;
        List<CodeQty> codes = new ArrayList<>();
        for (Leave l : in.leaves()) {
            BigDecimal q = "FULL".equals(l.part()) ? dv : half(dv);
            BigDecimal room = dv.subtract(leaveQty);
            q = q.min(room);
            if (q.signum() > 0) {
                codes.add(new CodeQty(l.code().code(), q));
                leaveQty = leaveQty.add(q);
            }
        }
        BigDecimal scheduled = shift.stdHours();
        if (leaveQty.compareTo(dv) >= 0) {
            return new DayResult(dayType, "ON_LEAVE", codes.getFirst().code(), codes, firstIn, lastOut, scheduled,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    (int) presenceMin, BigDecimal.ZERO, exceptions, false, in.manualOverride(), spans(in, presence));
        }
        boolean halfLeave = leaveQty.signum() > 0;
        int requiredMin = halfLeave ? workingMin / 2 : workingMin;

        long worked = Math.max(0, Intervals.length(work) - Math.min(flexUnpaid, Intervals.length(work)));
        worked = round(worked, shift.roundingRule());
        long paidBreak = Intervals.length(Intervals.intersect(inShift, paidFixed))
                + (Intervals.length(work) > flexPaid ? flexPaid : 0);
        long late = 0;
        long early = 0;
        long gracedLate = 0;
        long gracedEarly = 0;
        if (!presence.isEmpty() && !halfLeave) {
            long in0 = presence.getFirst().start();
            long out0 = presence.getLast().end();
            if (in0 > s) {
                if (in0 - s > shift.graceInMin()) {
                    late = in0 - s;
                } else {
                    gracedLate = in0 - s;
                }
            }
            if (out0 < e) {
                if (e - out0 > shift.graceOutMin()) {
                    early = e - out0;
                } else {
                    gracedEarly = e - out0;
                }
            }
        }
        // statutory short paid breaks (BR-ATT-035), only on days with some presence
        int protectedCredit = 0;
        if (!presence.isEmpty() && in.protectedMin() > 0) {
            long shortfall = Math.max(0, requiredMin - worked);
            protectedCredit = (int) Math.min(in.protectedMin(), shortfall);
            worked += protectedCredit;
            long rest = protectedCredit;
            long fromEarly = Math.min(rest, early);
            early -= fromEarly;
            rest -= fromEarly;
            late -= Math.min(rest, late);
            if (early > 0 && early <= shift.graceOutMin()) {
                gracedEarly = early;
                early = 0;
            }
            if (late > 0 && late <= shift.graceInMin()) {
                gracedLate = late;
                late = 0;
            }
        }
        long absent = Math.max(0, requiredMin - worked - gracedLate - gracedEarly);

        int fullMin = shift.minMinutesFullDay() != null ? shift.minMinutesFullDay()
                : Math.max(0, workingMin - shift.graceInMin() - shift.graceOutMin());
        int halfMin = shift.minMinutesHalfDay() != null ? shift.minMinutesHalfDay() : fullMin / 2;
        BigDecimal workValue;
        if (halfLeave) {
            workValue = worked >= halfMin ? dv.subtract(leaveQty) : BigDecimal.ZERO;
        } else {
            workValue = worked >= fullMin ? dv : worked >= halfMin ? half(dv) : BigDecimal.ZERO;
        }
        if (workValue.signum() > 0) {
            codes.add(new CodeQty(WORK_CODE, workValue));
        }
        BigDecimal uncovered = dv.subtract(leaveQty).subtract(workValue);
        boolean incomplete = exceptions.contains("MISSING_IN") || exceptions.contains("MISSING_OUT");
        if (uncovered.signum() > 0 && !incomplete) {
            codes.add(new CodeQty(ABSENT_CODE, uncovered));
            if (workValue.signum() == 0 && !halfLeave) {
                exceptions.add("ABSENT_NO_CODE");
            }
        }
        if (late > 0) {
            exceptions.add("LATE");
        }
        if (early > 0) {
            exceptions.add("EARLY");
        }
        String status;
        if (incomplete) {
            status = "INCOMPLETE";
        } else if (workValue.signum() == 0) {
            status = halfLeave ? "PARTIAL" : "ABSENT";
        } else {
            status = uncovered.signum() == 0 ? "PRESENT" : "PARTIAL";
        }
        List<Span> night = night(in, s, e);
        long nightMin = night.isEmpty() ? 0 : Intervals.length(Intervals.intersect(work, night));
        codes.sort(Comparator.comparing(CodeQty::qty).reversed());
        boolean blocking = exceptions.stream().anyMatch(DayModel.BLOCKING::contains);
        return new DayResult(dayType, status, codes.isEmpty() ? null : codes.getFirst().code(), codes, firstIn,
                lastOut, scheduled, hours(worked), hours(paidBreak), protectedCredit, (int) late, (int) early,
                hours(absent), hours(nightMin), (int) Math.max(0, otCandidate), workValue, exceptions, blocking,
                in.manualOverride(), spans(in, outside));
    }

    // ------------------------------------------------------------------------------------------------ pairing

    /**
     * FIRST_LAST: the first punch is the in, the last the out. ALL_PAIRS: consecutive punches form pairs.
     * BY_DIRECTION: an IN opens, an OUT closes (UNKNOWN toggles). A punch left without its partner yields
     * MISSING_IN / MISSING_OUT.
     */
    static List<Span> pair(List<Punch> punches, String mode, DayInput in, List<String> exceptions) {
        List<Span> out = new ArrayList<>();
        if (punches.isEmpty()) {
            return out;
        }
        List<Long> m = punches.stream().map(p -> PunchAttachment.minuteOf(p.at(), in.workDate(), in.zone()))
                .toList();
        switch (mode == null ? "FIRST_LAST" : mode) {
            case "ALL_PAIRS" -> {
                for (int i = 0; i + 1 < m.size(); i += 2) {
                    out.add(new Span(m.get(i), m.get(i + 1)));
                }
                if (m.size() % 2 == 1) {
                    exceptions.add("OUT".equals(punches.getLast().direction()) ? "MISSING_IN" : "MISSING_OUT");
                }
            }
            case "BY_DIRECTION" -> {
                Long open = null;
                boolean missingIn = false;
                boolean missingOut = false;
                for (int i = 0; i < m.size(); i++) {
                    String d = punches.get(i).direction();
                    boolean isIn = "IN".equals(d) || (!"OUT".equals(d) && open == null);
                    if (isIn) {
                        if (open != null) {
                            missingOut = true;
                        }
                        open = m.get(i);
                    } else if (open == null) {
                        missingIn = true;
                    } else {
                        out.add(new Span(open, m.get(i)));
                        open = null;
                    }
                }
                if (open != null) {
                    missingOut = true;
                }
                if (missingIn) {
                    exceptions.add("MISSING_IN");
                }
                if (missingOut) {
                    exceptions.add("MISSING_OUT");
                }
            }
            default -> {
                if (m.size() == 1) {
                    exceptions.add("OUT".equals(punches.getFirst().direction()) ? "MISSING_IN" : "MISSING_OUT");
                } else {
                    out.add(new Span(m.getFirst(), m.getLast()));
                }
            }
        }
        return Intervals.normalise(out);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The night windows touching {@code [s - 1 day, e + 1 day]} on the work date's timeline. */
    static List<Span> night(DayInput in, long s, long e) {
        LocalTime ns = in.nightStart();
        LocalTime ne = in.nightEnd();
        if (ns == null || ne == null) {
            return List.of();
        }
        long start = ns.toSecondOfDay() / 60;
        long len = Math.floorMod(ne.toSecondOfDay() / 60 - start, 1440);
        if (len == 0) {
            return List.of();
        }
        List<Span> out = new ArrayList<>();
        for (int k = -1; k <= 2; k++) {
            out.add(new Span(k * 1440L + start, k * 1440L + start + len));
        }
        return Intervals.normalise(out);
    }

    static long round(long minutes, String rule) {
        if (rule == null) {
            return minutes;
        }
        Matcher m = ROUNDING.matcher(rule.strip().toUpperCase(java.util.Locale.ROOT));
        if (!m.matches() || Integer.parseInt(m.group(2)) == 0) {
            return minutes;
        }
        long n = Integer.parseInt(m.group(2));
        return switch (m.group(1)) {
            case "DOWN" -> minutes / n * n;
            case "UP" -> (minutes + n - 1) / n * n;
            default -> (minutes + n / 2) / n * n;
        };
    }

    private static List<DayModel.TimeSpan> spans(DayInput in, List<Span> spans) {
        return Intervals.normalise(spans).stream()
                .map(x -> new DayModel.TimeSpan(instant(in, x.start()), instant(in, x.end()))).toList();
    }

    private static Instant instant(DayInput in, long minute) {
        return in.workDate().atStartOfDay(in.zone()).toInstant().plusSeconds(minute * 60);
    }

    static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal half(BigDecimal dv) {
        return dv.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static DayResult empty(String dayType, String status, String code, BigDecimal qty, List<String> ex,
            boolean manual) {
        return new DayResult(dayType, status, code, List.of(new CodeQty(code, qty)), null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, ex,
                false, manual, List.of());
    }
}
