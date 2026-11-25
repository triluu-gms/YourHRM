package vn.yourhrm.attendance.internal.application.period;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.application.timesheet.LockedDayChangeSink;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore;

/**
 * Records a retro request ({@code att.retro_request}) when daily processing sees changed inputs on a day of a LOCKED
 * period (BR-ATT-071). One PENDING request per employment x period, widened to the changed days; the next close of the
 * legal entity x pay group applies it.
 */
@Component
class RetroRecorder implements LockedDayChangeSink {

    private final AttendanceResultStore store;

    RetroRecorder(AttendanceResultStore store) {
        this.store = store;
    }

    @Override
    public void lockedDayChanged(UUID employmentId, UUID periodId, LocalDate workDate) {
        store.recordRetro(employmentId, periodId, workDate, "RECALCULATION");
    }
}
