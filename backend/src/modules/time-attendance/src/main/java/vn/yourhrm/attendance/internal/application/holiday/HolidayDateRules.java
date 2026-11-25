package vn.yourhrm.attendance.internal.application.holiday;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.DateEntry;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Input rules of a holiday date (BR-ATT-001/003/005). The DDL CHECKs back them up; these give readable 422s.
 * Day swaps ({@code SWAP_OFF} / {@code SWAP_WORK}, BR-ATT-004) are Phase 2 and refused.
 */
final class HolidayDateRules {

    static final Set<String> PHASE1_DAY_TYPES = Set.of("PUBLIC", "SUBSTITUTE", "COMPANY");
    static final Set<String> PHASE2_DAY_TYPES = Set.of("SWAP_OFF", "SWAP_WORK");
    static final Set<String> ELIGIBILITIES = Set.of("ALL", "FOREIGNERS");

    private static final Pattern HOLIDAY_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    static final Pattern CALENDAR_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,39}$");

    private HolidayDateRules() {
    }

    /** Normalises and validates {@code e}; returns the normalised entry. */
    static DateEntry validated(DateEntry e) {
        if (e.holidayDate() == null) {
            throw invalid("holiday-date-required", "The date is required");
        }
        String type = upper(e.dayType());
        if (type != null && PHASE2_DAY_TYPES.contains(type)) {
            throw ApiException.unprocessable("day-swap-not-available",
                    "Day swaps (" + type + ") are not available yet (BR-ATT-004, Phase 2)");
        }
        if (type == null || !PHASE1_DAY_TYPES.contains(type)) {
            throw invalid("invalid-day-type", "The day type must be one of " + PHASE1_DAY_TYPES);
        }
        if (e.nameVi() == null || e.nameVi().isBlank()) {
            throw invalid("holiday-name-required", "The Vietnamese name is required");
        }
        String code = upper(e.holidayCode());
        if (code != null && !HOLIDAY_CODE.matcher(code).matches()) {
            throw invalid("invalid-holiday-code", "The holiday code must be upper-case letters, digits or '_'");
        }
        String eligibility = e.eligibility() == null ? "ALL" : upper(e.eligibility());
        if (!ELIGIBILITIES.contains(eligibility)) {
            throw invalid("invalid-eligibility", "Eligibility must be ALL or FOREIGNERS");
        }
        String nationality = upper(e.nationalityCode());
        if ("FOREIGNERS".equals(eligibility)) {
            if (nationality == null || !COUNTRY.matcher(nationality).matches() || "VN".equals(nationality)) {
                throw invalid("nationality-required",
                        "A day for foreign employees needs their nationality (ISO 3166 alpha-2, not VN), BR-ATT-005");
            }
        } else if (nationality != null) {
            throw invalid("nationality-not-allowed", "A nationality is only given for days of foreign employees");
        }
        if ((e.lunarMonth() == null) != (e.lunarDay() == null)) {
            throw invalid("invalid-lunar-date", "Give both the lunar month and day, or neither");
        }
        if (e.lunarMonth() != null && (e.lunarMonth() < 1 || e.lunarMonth() > 12 || e.lunarDay() < 1
                || e.lunarDay() > 30)) {
            throw invalid("invalid-lunar-date", "The lunar month is 1-12 and the lunar day 1-30");
        }
        if ("SUBSTITUTE".equals(type)) {
            if (e.substituteForDate() == null) {
                throw invalid("substitute-for-required", "A substitute day names the holiday it replaces");
            }
            if (!e.substituteForDate().isBefore(e.holidayDate())) {
                throw invalid("invalid-substitute-for", "A substitute day comes after the holiday it replaces");
            }
        } else if (e.substituteForDate() != null) {
            throw invalid("substitute-for-not-allowed", "Only a SUBSTITUTE day names the holiday it replaces");
        }
        return new DateEntry(e.holidayDate(), type, code, e.nameVi().strip(), blankToNull(e.nameEn()),
                e.lunarMonth(), e.lunarDay(), e.lunarLeapMonth(), e.substituteForDate(), e.paid(), eligibility,
                nationality, blankToNull(e.legalRef()));
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw invalid("reason-required", "A reason is required to change or cancel a holiday");
        }
        if (reason.length() > 500) {
            throw invalid("reason-too-long", "The reason has at most 500 characters");
        }
        return reason.strip();
    }

    static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(java.util.Locale.ROOT);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message, Map.of());
    }
}
