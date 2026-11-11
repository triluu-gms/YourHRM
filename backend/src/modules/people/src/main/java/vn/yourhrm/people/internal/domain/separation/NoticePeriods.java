package vn.yourhrm.people.internal.domain.separation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.function.Predicate;

/**
 * Notice arithmetic of BR-PPL-071 / US-PPL-062 (Labour Code 2019 Art. 35 / 36, Decree 145/2020 Art. 7). Pure: the
 * numbers (days per category, the long-definite threshold, special-trade rules) come from legal parameters and the
 * working-day calendar is a predicate.
 */
public final class NoticePeriods {

    private NoticePeriods() {
    }

    /** CALENDAR or WORKING days (DDL {@code notice_day_basis}). */
    public static final String CALENDAR = "CALENDAR";
    public static final String WORKING = "WORKING";

    /**
     * Notice categories of the contract in force on the notice date: indefinite, definite of at least the long
     * threshold (12-36 months), definite below it (incl. seasonal), probation (no notice, Art. 27), or none (no labour
     * contract: apprenticeship, internship, service agreement, or no contract on file).
     */
    public enum Category {
        INDEFINITE, DEFINITE_LONG, DEFINITE_SHORT, PROBATION, NOT_APPLICABLE
    }

    /**
     * @param contractTypeCode   {@code hr.contract.contract_type_code}, null when no contract is in force
     * @param probationEndDate   last probation day of the contract (agreement or clause), null without probation
     * @param longMinMonths      legal parameter: months from which a definite contract is "long" [12]
     */
    public static Category category(String contractTypeCode, LocalDate start, LocalDate end, LocalDate probationEndDate,
            LocalDate noticeDate, int longMinMonths) {
        if (contractTypeCode == null) {
            return Category.NOT_APPLICABLE;
        }
        if ("PROBATION".equals(contractTypeCode)
                || probationEndDate != null && !noticeDate.isAfter(probationEndDate)) {
            return Category.PROBATION;
        }
        return switch (contractTypeCode) {
            case "INDEFINITE" -> Category.INDEFINITE;
            case "DEFINITE", "SEASONAL" -> end == null || termMonths(start, end) >= longMinMonths
                    ? Category.DEFINITE_LONG : Category.DEFINITE_SHORT;
            default -> Category.NOT_APPLICABLE;
        };
    }

    /** Whole months of the term {@code [start, end]} (end INCLUSIVE). */
    public static long termMonths(LocalDate start, LocalDate end) {
        return Period.between(start, end.plusDays(1)).toTotalMonths();
    }

    /** Calendar days of the term {@code [start, end]} (end INCLUSIVE). */
    public static long termDays(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    /** {@code ceil(term days x fraction)}: special trades with a contract under 12 months (e.g. one quarter). */
    public static int fractionOfTerm(LocalDate start, LocalDate end, BigDecimal fraction) {
        return BigDecimal.valueOf(termDays(start, end)).multiply(fraction).setScale(0, RoundingMode.CEILING)
                .intValueExact();
    }

    /**
     * Notice actually given: the days after the notice date up to and including the termination date, counted as
     * calendar days or as working days of the calendar.
     */
    public static int given(LocalDate noticeDate, LocalDate terminationDate, String basis,
            Predicate<LocalDate> isWorkingDay) {
        if (terminationDate.isBefore(noticeDate)) {
            return 0;
        }
        if (WORKING.equals(basis)) {
            int n = 0;
            for (LocalDate d = noticeDate.plusDays(1); !d.isAfter(terminationDate); d = d.plusDays(1)) {
                if (isWorkingDay.test(d)) {
                    n++;
                }
            }
            return n;
        }
        return (int) ChronoUnit.DAYS.between(noticeDate, terminationDate);
    }
}
