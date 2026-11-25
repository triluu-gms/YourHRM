package vn.yourhrm.attendance.internal.application.period;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.PayrollInbound;
import vn.yourhrm.attendance.internal.infra.AttInbox;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore.Period;

/**
 * T&amp;A's handlers of Payroll's facts ({@link PayrollInbound}), each delivery through the inbox:
 * <ul>
 *   <li>{@code pay.PayCalendarPublished} (ATT-C-18, BR-ATT-072): creates the OPEN periods per legal entity x pay group,
 *       with the OT cut-off, close deadline and the standard-day method and value (BR-ATT-016). A republished calendar
 *       updates a period: boundaries only while it is OPEN without a result (DB guard), the method / value while it is
 *       OPEN or IN_REVIEW. A CLOSED / LOCKED period keeps its values: the change applies to the next period (D2 Q2);
 *       Payroll reopens through T&amp;A to change an open one. Days computed before the period existed are attached
 *       to it.</li>
 *   <li>{@code pay.PayGroupAssigned} (ATT-C-24) → {@code att.ext_pay_pay_group_assignment}.</li>
 *   <li>{@code pay.PayrollRunStatusChanged} (ATT-C-19) → {@link CloseHoldService#runStatusChanged}.</li>
 * </ul>
 */
@Service
public class PayrollFeedService implements PayrollInbound {

    static final String CALENDAR_CONSUMER = "att.pay-calendar";
    static final String PAY_GROUP_CONSUMER = "att.pay-group-assignment";
    static final String RUN_CONSUMER = "att.payroll-run";

    private static final Logger log = LoggerFactory.getLogger(PayrollFeedService.class);

    private final AttInbox inbox;
    private final AttendancePeriodStore periods;
    private final CloseHoldService holds;

    public PayrollFeedService(AttInbox inbox, AttendancePeriodStore periods, CloseHoldService holds) {
        this.inbox = inbox;
        this.periods = periods;
        this.holds = holds;
    }

    @Override
    @AttTransactional
    public String deliver(PayCalendarPublished e) {
        return inbox.handle(CALENDAR_CONSUMER, id("pay.PayCalendarPublished", e.eventId(), e),
                "pay.PayCalendarPublished", () -> calendar(e)).name();
    }

    @Override
    @AttTransactional
    public String deliver(PayGroupAssigned e) {
        return inbox.handle(PAY_GROUP_CONSUMER, id("pay.PayGroupAssigned", e.eventId(), e), "pay.PayGroupAssigned",
                () -> periods.upsertPayGroupAssignment(e.assignmentId(), e.employmentId(), e.payGroupId(),
                        e.legalEntityId(), e.validFrom(), e.validTo(), e.eventId(), e.sourceVersion())).name();
    }

    @Override
    @AttTransactional
    public String deliver(PayrollRunStatusChanged e) {
        return inbox.handle(RUN_CONSUMER, id("pay.PayrollRunStatusChanged", e.eventId(), e),
                "pay.PayrollRunStatusChanged", () -> holds.runStatusChanged(e)).name();
    }

    private boolean calendar(PayCalendarPublished e) {
        boolean any = false;
        String method = e.stdDayMethod();
        var value = method != null && method.startsWith("FIXED_") ? e.stdDayValue() : null;
        for (PayPeriod pp : e.periods()) {
            Optional<Period> existing = periods.byStart(e.legalEntityId(), e.payGroupId(), pp.periodStart());
            if (existing.isEmpty()) {
                UUID id = periods.insert(e.legalEntityId(), e.payGroupId(), pp.payPeriodId(), pp.periodStart(),
                        pp.periodEnd(), pp.otPeriodStart(), pp.otPeriodEnd(), method, value, pp.closeDeadline(),
                        e.eventId());
                periods.attachDays(periods.find(id).orElseThrow());
                any = true;
                continue;
            }
            Period p = existing.get();
            boolean boundariesChanged = !Objects.equals(p.periodEnd(), pp.periodEnd())
                    || !Objects.equals(p.otPeriodStart(), pp.otPeriodStart())
                    || !Objects.equals(p.otPeriodEnd(), pp.otPeriodEnd());
            boolean boundaries = boundariesChanged && "OPEN".equals(p.status()) && p.currentVersion() == 0;
            boolean methodChanged = !Objects.equals(p.stdDayMethod(), method)
                    || (value == null ? p.stdDayValue() != null
                            : p.stdDayValue() == null || value.compareTo(p.stdDayValue()) != 0);
            boolean methodApplies = methodChanged && !p.frozen();
            if (boundariesChanged && !boundaries) {
                log.warn("Pay calendar republished other boundaries for attendance period {} ({}, v{}): kept",
                        p.id(), p.status(), p.currentVersion());
            }
            if (methodChanged && !methodApplies) {
                log.info("Standard-day method of attendance period {} kept while {} (applies to the next period)",
                        p.id(), p.status());
            }
            periods.updateCalendar(p.id(), boundaries, pp.periodEnd(), pp.otPeriodStart(), pp.otPeriodEnd(),
                    methodApplies, method, value, pp.payPeriodId(), pp.closeDeadline(), e.eventId());
            if (!p.frozen()) {
                periods.attachDays(periods.find(p.id()).orElseThrow());
            }
            any = true;
        }
        return any;
    }

    private UUID id(String type, UUID eventId, Object event) {
        return eventId != null ? eventId : inbox.contentId(type, event);
    }
}
