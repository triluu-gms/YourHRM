package vn.yourhrm.attendance.internal.application.notify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.AttendanceReminderJobs;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore;
import vn.yourhrm.attendance.internal.infra.notify.ReminderStore;
import vn.yourhrm.attendance.internal.infra.notify.ReminderStore.DuePeriod;
import vn.yourhrm.attendance.internal.infra.notify.ReminderStore.ExpiringCarry;
import vn.yourhrm.attendance.internal.infra.notify.ReminderStore.MissingPunchDay;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Audience;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Notifications.SendRequest;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * T&amp;A's daily reminders (BR-ATT-082) behind {@link AttendanceReminderJobs}:
 * <ul>
 *   <li><b>Missing punch</b>: each employee whose day {@code today - 1} has MISSING_IN / MISSING_OUT (not in a
 *       CLOSED / LOCKED period), when {@code att.reminder_missing_punch} is on for the day's legal entity.</li>
 *   <li><b>Carried leave expiring</b>: each CARRY ledger row with days still unused that expires exactly
 *       {@code att.reminder_carry_expiry_days} days after {@code today} (one reminder per row; a run missed on that
 *       day is not caught up).</li>
 *   <li><b>Close deadline</b>: each OPEN / IN_REVIEW period whose close deadline is {@code att.reminder_close_days}
 *       days after {@code today}, and again on the deadline itself, to the holders of {@code att.period.close} for
 *       the legal entity.</li>
 * </ul>
 * Reads run in one read-only T&amp;A transaction; each notification is sent on its own (Core's transaction), and a
 * failure is logged and counted, never stops the run. Core de-duplicates per recipient and key.
 */
@Service
public class ReminderService implements AttendanceReminderJobs {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ReminderStore store;
    private final LeaveStore leave;
    private final Notifications notifications;
    private final Settings settings;
    private final AuthorizationService authz;
    private final TransactionTemplate readTx;

    public ReminderService(ReminderStore store, LeaveStore leave, Notifications notifications, Settings settings,
            AuthorizationService authz, @Qualifier(AttDb.TX) PlatformTransactionManager attTm) {
        this.store = store;
        this.leave = leave;
        this.notifications = notifications;
        this.settings = settings;
        this.authz = authz;
        this.readTx = new TransactionTemplate(attTm);
        this.readTx.setReadOnly(true);
    }

    /** The manual run (until the job scheduler exists): needs {@code att.reminder.run} with tenant-wide scope. */
    public Summary runInScope(LocalDate today) {
        if (!authz.hasTenantWidePermission(AttendancePermissions.REMINDER_RUN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "tenant-scope-required",
                    "Running the reminders needs att.reminder.run for the whole tenant");
        }
        return runDaily(today);
    }

    @Override
    public Summary runDaily(LocalDate today) {
        Plan plan = readTx.execute(s -> plan(today));
        int failures = 0;
        for (SendRequest r : plan.all()) {
            try {
                notifications.send(r);
            } catch (RuntimeException e) {
                failures++;
                log.warn("Reminder {} ({}) not sent: {}", r.eventCode(), r.dedupeKey(), e.getMessage());
            }
        }
        return new Summary(today, plan.missing().size(), plan.carry().size(), plan.close().size(), failures);
    }

    private record Plan(List<SendRequest> missing, List<SendRequest> carry, List<SendRequest> close) {

        List<SendRequest> all() {
            return java.util.stream.Stream.of(missing, carry, close).flatMap(List::stream).toList();
        }
    }

    private Plan plan(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        Map<UUID, Boolean> missingOn = new HashMap<>();
        Map<UUID, Long> carryDays = new HashMap<>();
        Map<UUID, Long> closeDays = new HashMap<>();
        Function<UUID, Settings.Scope> scope = le -> new Settings.Scope(le, null);

        List<SendRequest> missing = store.missingPunches(yesterday).stream()
                .filter(d -> missingOn.computeIfAbsent(d.legalEntityId(),
                        le -> settings.booleanValue(AttendanceNotifications.SETTING_MISSING_PUNCH, scope.apply(le),
                                today)))
                .map(this::missingPunch).toList();

        List<SendRequest> carry = store.carriesExpiring(today.plusDays(1), today.plusDays(366)).stream()
                .filter(c -> {
                    long n = carryDays.computeIfAbsent(c.legalEntityId(), le -> settings.longValue(
                            AttendanceNotifications.SETTING_CARRY_DAYS, scope.apply(le), today));
                    return n > 0 && c.expiresOn().equals(today.plusDays(n));
                })
                .map(c -> carry(c, LeaveRules.carriedToExpire(c.quantity(),
                        leave.takenBefore(c.employmentId(), c.leaveTypeId(), c.leaveYear(), c.expiresOn()))))
                .filter(r -> r != null).toList();

        List<SendRequest> close = store.periodsDue(today, today.plusDays(366)).stream()
                .filter(p -> {
                    long n = closeDays.computeIfAbsent(p.legalEntityId(), le -> settings.longValue(
                            AttendanceNotifications.SETTING_CLOSE_DAYS, scope.apply(le), today));
                    return n > 0 && (p.closeDeadline().equals(today.plusDays(n)) || p.closeDeadline().equals(today));
                })
                .map(p -> closeDue(p, today)).toList();
        return new Plan(missing, carry, close);
    }

    private SendRequest missingPunch(MissingPunchDay d) {
        return new SendRequest(AttendanceNotifications.MISSING_PUNCH, "att",
                new Recipients(null, List.of(Audience.employee(d.employmentId()))),
                Map.of("workDate", VN_DATE.format(d.workDate())), "att.daily_timesheet", d.employmentId(),
                "/attendance/me/timesheets/" + d.workDate(), "att-missing-punch:" + d.employmentId() + ":"
                        + d.workDate(), null);
    }

    private SendRequest carry(ExpiringCarry c, BigDecimal rest) {
        if (rest.signum() <= 0) {
            return null;
        }
        return new SendRequest(AttendanceNotifications.CARRY_EXPIRING, "att",
                new Recipients(null, List.of(Audience.employee(c.employmentId()))),
                Map.of("days", rest.stripTrailingZeros().toPlainString(), "expiresOn",
                        VN_DATE.format(c.expiresOn())),
                "att.leave_balance_ledger", c.id(), "/attendance/me/leave-balances",
                "att-carry-expiring:" + c.id(), null);
    }

    private SendRequest closeDue(DuePeriod p, LocalDate today) {
        return new SendRequest(AttendanceNotifications.CLOSE_DUE, "att",
                new Recipients(null, List.of(Audience.permission(AttendancePermissions.PERIOD_CLOSE,
                        new AuthorizationTarget.LegalEntity(p.legalEntityId())))),
                Map.of("periodStart", VN_DATE.format(p.periodStart()), "periodEnd", VN_DATE.format(p.periodEnd()),
                        "closeDeadline", VN_DATE.format(p.closeDeadline())),
                "att.attendance_period", p.id(), "/attendance/periods/" + p.id(),
                "att-close-due:" + p.id() + ":" + today, null);
    }
}
