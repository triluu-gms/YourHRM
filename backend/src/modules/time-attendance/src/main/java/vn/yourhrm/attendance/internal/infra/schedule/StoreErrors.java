package vn.yourhrm.attendance.internal.infra.schedule;

import org.springframework.dao.DataIntegrityViolationException;
import vn.yourhrm.shared.common.error.ApiException;

/** Maps a violated constraint to a 409; any other integrity error is rethrown unchanged. */
final class StoreErrors {

    private StoreErrors() {
    }

    static ApiException translate(DataIntegrityViolationException e, String constraint, String code, String message) {
        if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(constraint)) {
            return ApiException.conflict(code, message);
        }
        throw e;
    }
}
