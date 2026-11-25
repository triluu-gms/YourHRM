package vn.yourhrm.attendance.internal.application.schedule;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import vn.yourhrm.shared.common.error.ApiException;

/** Shared input rules of the schedule configuration (codes, names, statuses, errors). */
final class ScheduleInputs {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,39}$");

    private ScheduleInputs() {
    }

    static String code(String raw, String what) {
        String c = upperOrNull(raw);
        if (c == null || !CODE.matcher(c).matches()) {
            throw invalid("invalid-" + what + "-code",
                    "The " + what + " code is 1-40 upper-case letters, digits, '_' or '-'");
        }
        return c;
    }

    static String name(String nameVi) {
        if (nameVi == null || nameVi.isBlank()) {
            throw invalid("name-required", "The Vietnamese name is required");
        }
        return nameVi.strip();
    }

    static String status(String raw, String current) {
        String st = raw == null ? current : upperOrNull(raw);
        if (!Set.of("ACTIVE", "INACTIVE").contains(st)) {
            throw invalid("invalid-status", "The status is ACTIVE or INACTIVE");
        }
        return st;
    }

    static String optionalText(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static String upperOrNull(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message);
    }

    static ApiException stale(String what) {
        return ApiException.conflict("stale-version", "The " + what + " was changed by someone else; reload it");
    }
}
