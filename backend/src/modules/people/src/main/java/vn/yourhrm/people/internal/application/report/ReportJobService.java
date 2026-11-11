package vn.yourhrm.people.internal.application.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Audience;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.ReportJobs;
import vn.yourhrm.people.internal.application.report.LabourReportService.Period;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Deadline alerts of the statutory labour reports behind {@link ReportJobs} (BR-PPL-141/142, US-PPL-069/070): for every
 * legal entity with current employees, the 6-monthly report periods of the legal pack and the monthly fluctuation
 * notices of the last months; when the days left before the due date equal one of the tenant offsets
 * ({@code hr.statutory_report.alert_days}) and the report is not submitted, the holders of
 * {@code hr.statutory_report.execute} of the legal entity are alerted (deduplicated per offset). A missing legal
 * parameter skips that report type (it cannot be computed).
 */
@Service
public class ReportJobService implements ReportJobs {

    private static final Logger log = LoggerFactory.getLogger(ReportJobService.class);

    private final HrDb db;
    private final LabourReportService reports;
    private final Notifications notifications;
    private final Settings settings;
    private final BusinessClock clock;

    public ReportJobService(HrDb db, LabourReportService reports, Notifications notifications, Settings settings,
            BusinessClock clock) {
        this.db = db;
        this.reports = reports;
        this.notifications = notifications;
        this.settings = settings;
        this.clock = clock;
    }

    public Summary runDaily() {
        return runDaily(clock.today());
    }

    @Override
    @HrTransactional(readOnly = true)
    public Summary runDaily(LocalDate today) {
        List<UUID> entities = db.jdbc().sql("""
                        select distinct legal_entity_id from hr.employment
                         where tenant_id = :t and status in ('ACTIVE','SUSPENDED')""")
                .param("t", tenant()).query(UUID.class).list();
        int alerts = 0;
        for (UUID le : entities) {
            Set<Integer> offsets = offsets(le, today);
            if (offsets.isEmpty()) {
                continue;
            }
            List<Object[]> due = new ArrayList<>();
            try {
                for (int y = today.getYear() - 1; y <= today.getYear() + 1; y++) {
                    for (String code : reports.sixMonthCodes(le, y)) {
                        due.add(new Object[] {ReportKeys.LABOUR_CHANGE_6M, reports.sixMonthPeriod(le, y, code)});
                    }
                }
            } catch (ApiException e) {
                log.debug("6-monthly report deadlines of {} skipped: {}", le, e.getMessage());
            }
            try {
                for (int m = -2; m <= 0; m++) {
                    due.add(new Object[] {ReportKeys.LABOUR_FLUCTUATION_MONTHLY,
                            reports.monthPeriod(le, YearMonth.from(today).plusMonths(m))});
                }
            } catch (ApiException e) {
                log.debug("Monthly fluctuation deadlines of {} skipped: {}", le, e.getMessage());
            }
            for (Object[] d : due) {
                String type = (String) d[0];
                Period p = (Period) d[1];
                int left = (int) ChronoUnit.DAYS.between(today, p.due());
                if (!offsets.contains(left) || submitted(le, type, p.start())) {
                    continue;
                }
                notify(le, type, p, left);
                alerts++;
            }
        }
        return new Summary(alerts);
    }

    private boolean submitted(UUID le, String type, LocalDate start) {
        return db.jdbc().sql("""
                        select exists (select 1 from hr.statutory_report where tenant_id = :t and legal_entity_id = :le
                                         and report_type = :type and period_start = :s
                                         and status in ('SUBMITTED','ACKNOWLEDGED'))""")
                .param("t", tenant()).param("le", le).param("type", type).param("s", start)
                .query(Boolean.class).single();
    }

    private void notify(UUID le, String type, Period p, int left) {
        boolean sixMonth = ReportKeys.LABOUR_CHANGE_6M.equals(type);
        String period = sixMonth ? ExcelSheet.date(p.start()) + " - " + ExcelSheet.date(p.end()) : p.code();
        try {
            notifications.send(new Notifications.SendRequest(ReportKeys.EVENT_REPORT_DUE, "hr",
                    new Recipients(null, List.of(Audience.permission(PeoplePermissions.STATUTORY_REPORT_EXECUTE,
                            new AuthorizationTarget.LegalEntity(le)))),
                    Map.of("reportVi", sixMonth ? "Báo cáo tình hình thay đổi về lao động"
                                    : "Thông báo biến động lao động",
                            "reportEn", sixMonth ? "6-monthly labour change report" : "monthly labour fluctuation notice",
                            "period", period, "dueDate", p.due().toString(), "daysLeft", left),
                    null, null, "/people/statutory-reports?legalEntityId=" + le,
                    "report-due:" + le + ":" + type + ":" + p.start() + ":" + left, null));
        } catch (RuntimeException e) {
            log.warn("Report deadline alert for {} {} failed: {}", type, p.start(), e.getMessage());
        }
    }

    private Set<Integer> offsets(UUID le, LocalDate today) {
        Object v = settings.value(ReportKeys.SETTING_ALERT_DAYS, new Settings.Scope(le, null), today);
        Set<Integer> out = new HashSet<>();
        if (v instanceof List<?> l) {
            l.forEach(o -> {
                if (o instanceof Number n) {
                    out.add(n.intValue());
                }
            });
        }
        return out;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
