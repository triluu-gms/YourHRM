package vn.yourhrm.people.internal.application.separation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.api.SeparationEvents.EmployeeTerminated;
import vn.yourhrm.people.api.SeparationJobs;
import vn.yourhrm.people.api.SeparationSettlements;
import vn.yourhrm.people.internal.application.assignment.RetroFlags;
import vn.yourhrm.people.internal.application.separation.SeparationRecords.SeparationView;
import vn.yourhrm.people.internal.application.separation.SeparationRecords.ServicePeriodView;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.assignment.ProcessedMarkers;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The daily separation job behind {@link SeparationJobs} (BR-PPL-075, US-PPL-066) and People's intake of Payroll's
 * final-settlement status behind {@link SeparationSettlements} (IN-19, display only).
 *
 * <p>Termination: every SCHEDULED separation whose termination date is before today is completed: the employment
 * becomes TERMINATED with {@code end_date} = termination date and {@code last_working_date} (the only writes of this
 * stream on {@code hr.employment}), and {@code people.EmployeeTerminated} carries the final-settlement trigger. With the
 * default termination date = last working day this is the day after the last working day ("LWD + 1").
 */
@Service
public class SeparationJobService implements SeparationJobs, SeparationSettlements {

    private static final Logger log = LoggerFactory.getLogger(SeparationJobService.class);
    static final String SETTLEMENT_CONSUMER = "hr.separation.final_settlement";

    private final HrDb db;
    private final SeparationRecords records;
    private final SeparationService separations;
    private final SeparationReasons reasons;
    private final HrOutbox outbox;
    private final RetroFlags retro;
    private final ProcessedMarkers markers;
    private final Settings settings;
    private final AuditLog audit;
    private final BusinessClock clock;

    public SeparationJobService(HrDb db, SeparationRecords records, SeparationService separations,
            SeparationReasons reasons, HrOutbox outbox, RetroFlags retro, ProcessedMarkers markers, Settings settings, AuditLog audit, BusinessClock clock) {
        this.db = db;
        this.records = records;
        this.separations = separations;
        this.reasons = reasons;
        this.outbox = outbox;
        this.retro = retro;
        this.markers = markers;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /** Today's run (scheduler entry point without a date). */
    public Summary runDaily() {
        return runDaily(clock.today());
    }

    @Override
    @HrTransactional
    public Summary runDaily(LocalDate today) {
        List<UUID> due = db.jdbc().sql("""
                        select id from hr.separation where tenant_id = :t and status = 'SCHEDULED'
                           and termination_date < :d order by termination_date, id""")
                .param("t", tenant()).param("d", today).query(UUID.class).list();
        int terminated = 0;
        for (UUID id : due) {
            if (complete(id)) {
                terminated++;
            }
        }
        return new Summary(terminated, settlementAlerts(today));
    }

    private boolean complete(UUID id) {
        SeparationView s = records.lock(id);
        if (!"SCHEDULED".equals(s.status())) {
            return false;
        }
        int updated = db.jdbc().sql("""
                        update hr.employment set status = 'TERMINATED', end_date = :end, last_working_date = :lwd,
                               version = version + 1
                         where tenant_id = :t and id = :e and status in ('ACTIVE','SUSPENDED')""")
                .param("end", s.terminationDate()).param("lwd", s.lastWorkingDate()).param("t", tenant())
                .param("e", s.employmentId()).update();
        if (updated == 0 && !"TERMINATED".equals(s.employmentStatus())) {
            log.warn("Separation {} not completed: employment {} is {}", id, s.employeeNo(), s.employmentStatus());
            return false;
        }
        records.refreshDerivedPeriods(s);
        db.jdbc().sql("""
                        update hr.separation set status = 'COMPLETED', version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("t", tenant()).param("id", id).update();
        SeparationView done = records.require(id);
        String statutoryCode = null;
        try {
            statutoryCode = reasons.rule(reasons.requireGround(done.legalGroundCode()), done.legalEntityId(),
                    done.noticeDate()).statutoryCode();
        } catch (RuntimeException e) {
            log.warn("No statutory code for separation {}: {}", id, e.getMessage());
        }
        outbox.publish(new EmployeeTerminated(tenant(), done.legalEntityId(), done.personId(), done.employmentId(),
                done.terminationDate(), done.id(), done.decisionId(), done.separationType(), done.legalGroundCode(),
                statutoryCode, done.lastWorkingDate(), done.terminationDate(), done.noticeShortfallDays(),
                done.severanceType(), done.severanceEligibility(),
                done.servicePeriods().stream().map(ServicePeriodView::event).toList(), done.accessRevocationDate(),
                done.rehireEligible(), retro.of(done.legalEntityId(), done.terminationDate()).retro()));
        audit.record(new BusinessAction("TERMINATE_EMPLOYMENT", "hr", "employment", done.employmentId(),
                done.legalEntityId(), Map.of("separationId", id.toString(),
                        "terminationDate", done.terminationDate().toString())));
        return true;
    }

    /** Alerts HR on the tenant offsets before Payroll's settlement due date (once per separation and offset). */
    private int settlementAlerts(LocalDate today) {
        record Due(UUID id, UUID le, LocalDate due) {
        }
        List<Due> rows = db.jdbc().sql("""
                        select s.id, e.legal_entity_id, s.settlement_due_date
                          from hr.separation s join hr.employment e on e.tenant_id = s.tenant_id and e.id = s.employment_id
                         where s.tenant_id = :t and s.status = 'COMPLETED' and s.settlement_due_date >= :d""")
                .param("t", tenant()).param("d", today)
                .query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class)))
                .list();
        Map<UUID, Set<Integer>> offsets = new HashMap<>();
        int alerts = 0;
        for (Due d : rows) {
            int left = (int) ChronoUnit.DAYS.between(today, d.due());
            if (!offsets.computeIfAbsent(d.le(), le -> offsets(le, today)).contains(left)) {
                continue;
            }
            SeparationView s = records.require(d.id());
            separations.notifyHr(SeparationKeys.EVENT_SETTLEMENT_DUE, s, Map.of("employeeNo", s.employeeNo(),
                    "terminationDate", s.terminationDate().toString(), "dueDate", d.due().toString(),
                    "daysLeft", left), "settlement-due:" + d.id() + ":" + left);
            alerts++;
        }
        return alerts;
    }

    private Set<Integer> offsets(UUID legalEntityId, LocalDate today) {
        Object v = settings.value(SeparationKeys.SETTING_SETTLEMENT_ALERT_DAYS, new Settings.Scope(legalEntityId, null),
                today);
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

    // ----------------------------------------------------------------------------------------- IN-19 intake

    @Override
    @HrTransactional
    public boolean finalSettlementStatusChanged(FinalSettlementStatus st) {
        markers.lock(SETTLEMENT_CONSUMER, st.eventId());
        if (markers.exists(SETTLEMENT_CONSUMER, st.eventId())) {
            return false;
        }
        List<UUID> ids = db.jdbc().sql("""
                        select id from hr.separation where tenant_id = :t and employment_id = :e
                           and status in ('SCHEDULED','COMPLETED') order by created_at desc limit 1""")
                .param("t", tenant()).param("e", st.employmentId()).query(UUID.class).list();
        if (ids.isEmpty()) {
            markers.mark(SETTLEMENT_CONSUMER, st.eventId(), "pay.FinalSettlementStatusChanged", "SKIPPED");
            return false;
        }
        db.jdbc().sql("""
                        update hr.separation set payroll_result_ref = :ref, settlement_due_date = :due,
                               version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("ref", st.payrollResultRef()).param("due", st.settlementDueDate()).param("t", tenant())
                .param("id", ids.getFirst()).update();
        SeparationView s = records.require(ids.getFirst());
        Map<String, String> details = new HashMap<>();
        details.put("payrollStatus", String.valueOf(st.status()));
        details.put("settlementDueDate", String.valueOf(st.settlementDueDate()));
        audit.record(new BusinessAction("RECORD_FINAL_SETTLEMENT_STATUS", "hr", "separation", s.id(),
                s.legalEntityId(), details));
        markers.mark(SETTLEMENT_CONSUMER, st.eventId(), "pay.FinalSettlementStatusChanged", "PROCESSED");
        return true;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
