package vn.yourhrm.attendance.internal.application.timesheet;

import java.time.LocalDate;
import java.util.UUID;

/**
 * In-process event inside T&amp;A only: a menstrual-break day was declared or withdrawn (BR-ATT-035). Daily processing
 * recomputes that day after commit. Never published to other modules (health-derived data, D7 minimal payloads).
 */
record MenstrualBreakChanged(UUID tenantId, UUID employmentId, LocalDate workDate) {
}
