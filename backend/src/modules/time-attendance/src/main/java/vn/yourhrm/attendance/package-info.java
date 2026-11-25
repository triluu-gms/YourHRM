/**
 * Time &amp; Attendance module (BRD 03-time-attendance, schema {@code att}): holiday calendars, shifts and schedules,
 * punch import, daily timesheets, overtime, leave, attendance periods and the versioned Attendance Result handed to
 * Payroll. Connects with its own pool and login role {@code yhrm_app_att} (ADR-0021). Only
 * {@code vn.yourhrm.attendance.api} is visible to other modules.
 */
@ApplicationModule(displayName = "Time & Attendance")
package vn.yourhrm.attendance;

import org.springframework.modulith.ApplicationModule;
