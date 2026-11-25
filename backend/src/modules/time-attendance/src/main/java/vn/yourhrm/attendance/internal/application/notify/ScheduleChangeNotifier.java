package vn.yourhrm.attendance.internal.application.notify;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.yourhrm.attendance.api.ScheduleAssignmentChanged;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Audience;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Notifications.SendRequest;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Tells the employee that HR assigned them a schedule by hand (BR-ATT-082 "công bố phân ca"; P1 has no rostering, so
 * a MANUAL schedule assignment is the published schedule). Only changes that reach today or later are announced;
 * rule regeneration and People-driven changes are not (they follow structure changes and would flood the inbox).
 * After commit; a failure is logged and never undoes the assignment.
 */
@Component
class ScheduleChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(ScheduleChangeNotifier.class);
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Notifications notifications;
    private final BusinessClock clock;

    ScheduleChangeNotifier(Notifications notifications, BusinessClock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onSchedule(ScheduleAssignmentChanged e) {
        if (!"MANUAL".equals(e.cause()) || !e.scheduled() || e.employmentId() == null || e.rangeFrom() == null
                || (e.rangeTo() != null && e.rangeTo().isBefore(clock.today()))) {
            return;
        }
        UUID tenant = e.tenantId();
        Runnable send = () -> notifications.send(new SendRequest(AttendanceNotifications.SCHEDULE_CHANGED, "att",
                new Recipients(null, List.of(Audience.employee(e.employmentId()))),
                Map.of("fromDate", VN_DATE.format(e.rangeFrom())), "att.schedule_assignment", e.assignmentKey(),
                "/attendance/me/schedule", "att-schedule:" + e.employmentId() + ":" + e.assignmentKey() + ":"
                        + e.rangeFrom(), null));
        try {
            if (TenantContext.isBound() && TenantContext.current().tenantId().equals(tenant)) {
                send.run();
            } else {
                TenantContext.runWith(new TenantContext(tenant, null, "att-notify", null, null), send);
            }
        } catch (RuntimeException ex) {
            log.warn("Schedule change of employment {} not notified: {}", e.employmentId(), ex.getMessage());
        }
    }
}
