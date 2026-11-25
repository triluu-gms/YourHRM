package vn.yourhrm.attendance.internal.application.timesheet;

import java.util.Locale;

/** Small input helpers of the timesheet stream. */
final class TimesheetInputs {

    private TimesheetInputs() {
    }

    static String text(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Upper case in the root locale ("ô" → "Ô" stays a valid code). */
    static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }
}
