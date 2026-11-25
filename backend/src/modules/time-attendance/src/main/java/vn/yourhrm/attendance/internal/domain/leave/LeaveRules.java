package vn.yourhrm.attendance.internal.domain.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * Pure leave arithmetic (BR-ATT-057..061, 067): leave years, request duration from the planned days, the annual
 * entitlement with its statutory floor and seniority steps, the ND145 half-month proration, the monthly accrual
 * steps, carry-over at year end and the termination settlement. No I/O; the statutory numbers are inputs read from the
 * Legal Parameter Service by the caller.
 */
public final class LeaveRules {

    private LeaveRules() {
    }

    public static final Set<String> UNITS = Set.of("DAY", "HALF_DAY", "HOUR");
    public static final Set<String> PARTS = Set.of("FULL", "AM", "PM");
    public static final Set<String> BASES = Set.of("WORKING_DAY", "CALENDAR_DAY");
    public static final Set<String> CATEGORIES = Set.of("ANNUAL", "PERSONAL_PAID", "PERSONAL_UNPAID", "SICK",
            "CHILD_SICK", "MATERNITY", "PATERNITY", "PRENATAL", "MISCARRIAGE", "WORK_ACCIDENT", "UNPAID_AGREED", "TOIL",
            "COMPANY_PAID", "OTHER");
    public static final Set<String> PAID_BY = Set.of("EMPLOYER", "SI_FUND", "UNPAID");
    public static final Set<String> PAYROLL_CATEGORIES = Set.of("PAID_LEAVE_DAYS", "SI_LEAVE_DAYS", "UNPAID_DAYS");
    public static final Set<String> ACCRUAL_METHODS = Set.of("MONTHLY", "ANNUAL_FRONT", "PRORATA", "NONE");
    public static final Set<String> PRORATION_RULES = Set.of("ND145_HALF_MONTH", "CALENDAR_DAYS", "NONE");
    public static final Set<String> EXPIRY_ACTIONS = Set.of("FORFEIT", "ENCASH", "FLAG");

    static final int SCALE = 2;

    // ------------------------------------------------------------------------------------------- leave years

    /** The leave year of a date: the calendar year in which that leave year starts (BR-ATT-060). */
    public static int leaveYearOf(LocalDate d, int startMonth) {
        return d.getMonthValue() >= startMonth ? d.getYear() : d.getYear() - 1;
    }

    public static LocalDate yearStart(int leaveYear, int startMonth) {
        return LocalDate.of(leaveYear, startMonth, 1);
    }

    /** Last day of the leave year (inclusive). */
    public static LocalDate yearEnd(int leaveYear, int startMonth) {
        return yearStart(leaveYear, startMonth).plusYears(1).minusDays(1);
    }

    // ------------------------------------------------------------------------------------------------ duration

    /**
     * One day of the requested range as planned: whether it is a working day of the employee (not a rest day, holiday
     * or substitute day) and its value (1, or 0.5 for a half working day).
     */
    public record PlanDay(LocalDate date, boolean workingDay, BigDecimal dayValue) {
    }

    /** A day the request covers and how much of it (1, 0.5 ...), after the counting basis. */
    public record CountedDay(LocalDate date, BigDecimal quantity, String part) {
    }

    /**
     * The days a day / half-day request counts (BR-ATT-061): working days only for the WORKING_DAY basis (rest days
     * and holidays count 0), every calendar day for CALENDAR_DAY (e.g. maternity). The first day counts half when it
     * starts in the afternoon, the last day half when it ends at noon.
     */
    public static List<CountedDay> countedDays(List<PlanDay> plan, String basis, String startPart, String endPart) {
        if (plan.isEmpty()) {
            return List.of();
        }
        LocalDate first = plan.getFirst().date();
        LocalDate last = plan.getLast().date();
        return plan.stream().map(p -> {
            BigDecimal value = "CALENDAR_DAY".equals(basis) ? BigDecimal.ONE
                    : p.workingDay() ? (p.dayValue() == null ? BigDecimal.ONE : p.dayValue()) : BigDecimal.ZERO;
            String part = "FULL";
            if (p.date().equals(first) && "PM".equals(startPart)) {
                part = "PM";
            } else if (p.date().equals(last) && "AM".equals(endPart)) {
                part = "AM";
            }
            if (first.equals(last) && "AM".equals(startPart) && !"PM".equals(endPart)) {
                part = "AM";
            }
            BigDecimal q = "FULL".equals(part) ? value : value.min(BigDecimal.ONE).multiply(new BigDecimal("0.5"));
            return new CountedDay(p.date(), q.setScale(SCALE, RoundingMode.HALF_UP), part);
        }).toList();
    }

    public static BigDecimal total(List<CountedDay> days) {
        return days.stream().map(CountedDay::quantity).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    // --------------------------------------------------------------------------------------------- entitlement

    /**
     * Annual entitlement of a year (BR-ATT-057): the larger of the statutory floor (12 / 14 / 16 days by the job flags,
     * from the legal pack) and the policy's base, plus {@code stepDays} for every full {@code stepYears} of service.
     * A policy may grant more, never less.
     */
    public static BigDecimal entitlement(BigDecimal floor, BigDecimal policyBase, int serviceYears, int stepYears,
            BigDecimal stepDays) {
        BigDecimal base = floor == null ? policyBase : policyBase == null ? floor : floor.max(policyBase);
        if (base == null) {
            return null;
        }
        BigDecimal seniority = stepYears <= 0 || stepDays == null ? BigDecimal.ZERO
                : stepDays.multiply(BigDecimal.valueOf(serviceYears / stepYears));
        return base.add(seniority).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Full years of service from {@code serviceStart} to {@code asOf}. */
    public static int serviceYears(LocalDate serviceStart, LocalDate asOf) {
        return serviceStart == null || asOf.isBefore(serviceStart) ? 0 : Period.between(serviceStart, asOf).getYears();
    }

    /** Full months of service from {@code serviceStart} to {@code asOf} (eligibility "min service months"). */
    public static long serviceMonths(LocalDate serviceStart, LocalDate asOf) {
        return serviceStart == null || asOf.isBefore(serviceStart) ? 0 : Period.between(serviceStart, asOf).toTotalMonths();
    }

    // --------------------------------------------------------------------------------------------- proration

    /**
     * Facts of one month for proration: the month's normal working days, and the days of it the employee was employed
     * and that count as working time (worked, or leave the law counts, BR-ATT-059).
     */
    public record MonthFacts(YearMonth month, int workingDays, int serviceDays, int calendarDays, int employedDays) {
    }

    /**
     * Whether the month earns leave (BR-ATT-058). ND145_HALF_MONTH: the employee worked at least half of the month's
     * normal working days (Nghị định 145 Art. 66); CALENDAR_DAYS: employed at least half of the calendar days;
     * NONE: any employed day.
     */
    public static boolean counts(MonthFacts m, String rule) {
        return switch (rule) {
            case "ND145_HALF_MONTH" -> m.workingDays() > 0
                    ? 2 * m.serviceDays() >= m.workingDays()
                    : 2 * m.employedDays() >= m.calendarDays();
            case "CALENDAR_DAYS" -> 2 * m.employedDays() >= m.calendarDays();
            default -> m.employedDays() > 0;
        };
    }

    /**
     * Amount accrued for the {@code k}-th counted month of a leave year (1-based): {@code E·k/12 − E·(k−1)/12}, each
     * rounded to 2 decimals, so twelve counted months add up to exactly E.
     */
    public static BigDecimal monthlyStep(BigDecimal annual, int k) {
        return share(annual, k).subtract(share(annual, k - 1));
    }

    /** {@code E·months/12} rounded to 2 decimals. */
    public static BigDecimal share(BigDecimal annual, int months) {
        if (months <= 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return annual.multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(12), SCALE, RoundingMode.HALF_UP);
    }

    // --------------------------------------------------------------------------------------------- year end

    /**
     * Split of a year-end balance (BR-ATT-060): {@code carried} moves to the next year (at most {@code carryMax}; a
     * negative balance is carried whole as debt), {@code remainder} expires, is encashed or is flagged.
     */
    public record YearEndSplit(BigDecimal carried, BigDecimal remainder) {
    }

    public static YearEndSplit yearEnd(BigDecimal balance, BigDecimal carryMax) {
        if (balance.signum() <= 0) {
            return new YearEndSplit(balance, BigDecimal.ZERO);
        }
        BigDecimal max = carryMax == null ? BigDecimal.ZERO : carryMax.max(BigDecimal.ZERO);
        BigDecimal carried = balance.min(max);
        return new YearEndSplit(carried, balance.subtract(carried));
    }

    /**
     * Carried days still unused at their expiry: taken days of the new year consume the carried days first
     * ({@code takenBeforeExpiry} positive).
     */
    public static BigDecimal carriedToExpire(BigDecimal carried, BigDecimal takenBeforeExpiry) {
        return carried.subtract(takenBeforeExpiry.max(BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }
}
