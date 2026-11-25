package vn.yourhrm.attendance.internal.application.notify;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Audience;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Notifications.SendRequest;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;

/**
 * Overtime cap threshold notifications (BR-ATT-082, BR-ATT-046): when a submitted request brings the monthly or annual
 * cap usage (approved + pending) to the warning threshold or above, the employee and the holders of
 * {@code att.overtime.cap_override} for the employee are told. Sent after the submit committed; at most once a day
 * per employment × cap period (Core's de-duplication).
 */
@Component
public class OvertimeCapNotifier {

    private static final Logger log = LoggerFactory.getLogger(OvertimeCapNotifier.class);
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Notifications notifications;

    OvertimeCapNotifier(Notifications notifications) {
        this.notifications = notifications;
    }

    /**
     * @param kind    MONTH or YEAR
     * @param percent usage after the request, in percent of the cap
     */
    public void thresholdReached(UUID employmentId, UUID legalEntityId, UUID orgUnitId, UUID locationId,
            LocalDate workDate, String kind, long percent) {
        LocalDate periodStart = "YEAR".equals(kind) ? workDate.withDayOfYear(1) : workDate.withDayOfMonth(1);
        SendRequest r = new SendRequest("YEAR".equals(kind) ? AttendanceNotifications.OT_YEAR_CAP
                : AttendanceNotifications.OT_MONTH_CAP, "att",
                new Recipients(null, List.of(Audience.employee(employmentId),
                        Audience.permission(AttendancePermissions.OVERTIME_CAP_OVERRIDE,
                                new AuthorizationTarget.Employee(employmentId, legalEntityId, orgUnitId,
                                        locationId)))),
                Map.of("percent", String.valueOf(percent), "workDate", VN_DATE.format(workDate)),
                "att.ot_cap_ledger", employmentId, "/attendance/employments/" + employmentId + "/overtime-caps",
                "att-ot-cap:" + employmentId + ":" + kind + ":" + periodStart, null);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(r);
                }
            });
        } else {
            send(r);
        }
    }

    private void send(SendRequest r) {
        try {
            notifications.send(r);
        } catch (RuntimeException e) {
            log.warn("Overtime cap notification {} not sent: {}", r.dedupeKey(), e.getMessage());
        }
    }
}
