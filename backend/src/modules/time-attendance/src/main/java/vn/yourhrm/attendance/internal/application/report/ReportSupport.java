package vn.yourhrm.attendance.internal.application.report;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import vn.yourhrm.shared.common.error.ApiException;

/** Shared argument checks of the report services. */
final class ReportSupport {

    private ReportSupport() {
    }

    /** {@code from} / {@code to} given, ordered, and at most {@code maxDays} days apart (inclusive). */
    static void range(LocalDate from, LocalDate to, int maxDays) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.unprocessable("invalid-range", "Give from and to (to on or after from)");
        }
        if (ChronoUnit.DAYS.between(from, to) >= maxDays) {
            throw ApiException.unprocessable("range-too-long", "The range is at most " + maxDays + " days");
        }
    }

    static String upper(String v) {
        return v == null || v.isBlank() ? null : v.strip().toUpperCase(Locale.ROOT);
    }

    static String oneOf(String v, String field, java.util.Set<String> allowed) {
        String u = upper(v);
        if (u != null && !allowed.contains(u)) {
            throw ApiException.unprocessable("invalid-" + field, field + " must be one of " + allowed);
        }
        return u;
    }
}
