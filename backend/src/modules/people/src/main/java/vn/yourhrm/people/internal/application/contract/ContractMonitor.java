package vn.yourhrm.people.internal.application.contract;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Audience;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.api.ContractEvents.ContractExpiring;
import vn.yourhrm.people.api.ContractJobs;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.application.contract.ContractSuspensionService.SuspensionView;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The daily contract monitor (BR-PPL-052/054/056, US-PPL-049/051/054) behind {@link ContractJobs}. Alerts go to the
 * holders of {@code hr.contract.update} (HR) and {@code hr.contract.view} (e.g. managers with a reports scope) whose
 * data scope covers the employee, through Core's notification centre. Expiry alerts are published once per contract
 * and offset ({@code people.ContractExpiring}, deduplicated on the outbox). The auto-conversion flag is recorded once in
 * {@code hr.contract.auto_conversion_due_since} (first day the contract counts as converted) and published with it;
 * probation and return-to-work reminders repeat daily until HR records the result / return (Core deduplicates within a
 * day).
 */
@Service
public class ContractMonitor implements ContractJobs {

    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final HrDb db;
    private final HrOutbox outbox;
    private final Notifications notifications;
    private final Settings settings;
    private final PeopleLegalParameters legal;
    private final EmployeeAccess access;
    private final ContractSuspensionService suspensions;
    private final BusinessClock clock;

    public ContractMonitor(HrDb db, HrOutbox outbox, Notifications notifications, Settings settings,
            PeopleLegalParameters legal, EmployeeAccess access, ContractSuspensionService suspensions,
            BusinessClock clock) {
        this.db = db;
        this.outbox = outbox;
        this.notifications = notifications;
        this.settings = settings;
        this.legal = legal;
        this.access = access;
        this.suspensions = suspensions;
        this.clock = clock;
    }

    /** Today's run (scheduler entry point without a date). */
    public Summary runDaily() {
        return runDaily(clock.today());
    }

    @Override
    @HrTransactional
    public Summary runDaily(LocalDate today) {
        int started = suspensions.startDue(today);
        int expired = db.jdbc().sql("""
                        update hr.contract set status = 'EXPIRED', version = version + 1
                         where tenant_id = :t and status = 'ACTIVE' and end_date < :d""")
                .param("t", tenant()).param("d", today).update();
        int[] expiry = expiryAlerts(today);
        int probation = probationAlerts(today);
        int returns = returnAlerts(today);
        return new Summary(expired, expiry[0], expiry[1], probation, started, returns);
    }

    // ----------------------------------------------------------------------------------- expiry (BR-PPL-054)

    /**
     * {@code status}: the employment status (expiry rows) or the contract / suspension status (others);
     * {@code autoConversionDueSince}: expiry rows only.
     */
    record Row(UUID contractId, UUID employmentId, UUID personId, UUID legalEntityId, String employeeNo,
            String contractNo, String type, LocalDate endDate, String status, LocalDate autoConversionDueSince) {

        Row(UUID contractId, UUID employmentId, UUID personId, UUID legalEntityId, String employeeNo,
                String contractNo, String type, LocalDate endDate, String status) {
            this(contractId, employmentId, personId, legalEntityId, employeeNo, contractNo, type, endDate, status, null);
        }
    }

    private int[] expiryAlerts(LocalDate today) {
        List<Row> rows = db.jdbc().sql("""
                        select c.id, c.employment_id, e.person_id, e.legal_entity_id, e.employee_no, c.contract_no,
                               c.contract_type_code, c.end_date, e.status, c.auto_conversion_due_since
                          from hr.contract c
                          join hr.employment e on e.tenant_id = c.tenant_id and e.id = c.employment_id
                         where c.tenant_id = :t and c.end_date is not null and c.contract_type_code <> 'PROBATION'
                           and c.status in ('ACTIVE','SUSPENDED','EXPIRED') and e.status in ('ACTIVE','SUSPENDED','PRE_HIRE')
                           and""" + " " + ContractExpiryService.FOLLOWING + " is null")
                .param("t", tenant())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getObject(8, LocalDate.class), rs.getString(9),
                        rs.getObject(10, LocalDate.class)))
                .list();
        Map<UUID, TreeSet<Integer>> offsetsByLe = new HashMap<>();
        Map<UUID, Optional<Integer>> convertByLe = new HashMap<>();
        int alerts = 0;
        int flags = 0;
        for (Row r : rows) {
            long left = ChronoUnit.DAYS.between(today, r.endDate());
            if (left >= 0) {
                Integer offset = offsetsByLe.computeIfAbsent(r.legalEntityId(), le -> offsets(le, today)).ceiling((int) left);
                if (offset != null && !alreadyPublished(r, offset)) {
                    outbox.publish(new ContractExpiring(tenant(), r.legalEntityId(), r.personId(), r.employmentId(),
                            r.endDate(), r.contractId(), r.contractNo(), r.type(), r.endDate(), offset, false));
                    notify(ContractNotificationTemplates.CONTRACT_EXPIRING, r, today, Map.of("employeeNo",
                            r.employeeNo(), "contractNo", r.contractNo(), "endDate", VN.format(r.endDate()),
                            "daysLeft", left), "contract-expiring:" + r.contractId() + ":" + offset);
                    alerts++;
                }
            } else if (("DEFINITE".equals(r.type()) || "SEASONAL".equals(r.type())) && !"PRE_HIRE".equals(r.status())) {
                Optional<Integer> days = convertByLe.computeIfAbsent(r.legalEntityId(), le -> legal.find(
                        ContractKeys.DEFINITE_AUTO_CONVERT_DAYS, new Scope(le, null), today).map(v -> v.asInt()));
                if (days.isPresent() && -left > days.get() && r.autoConversionDueSince() == null
                        && flagAutoConversion(r, r.endDate().plusDays(days.get() + 1L))) {
                    outbox.publish(new ContractExpiring(tenant(), r.legalEntityId(), r.personId(), r.employmentId(),
                            r.endDate(), r.contractId(), r.contractNo(), r.type(), r.endDate(), (int) left, true));
                    notify(ContractNotificationTemplates.AUTO_CONVERSION_DUE, r, today, Map.of("employeeNo",
                            r.employeeNo(), "contractNo", r.contractNo(), "endDate", VN.format(r.endDate())),
                            "contract-auto-conversion:" + r.contractId());
                    flags++;
                }
            }
        }
        return new int[] {alerts, flags};
    }

    private TreeSet<Integer> offsets(UUID legalEntityId, LocalDate today) {
        TreeSet<Integer> out = new TreeSet<>();
        Object v = settings.value(ContractKeys.SETTING_EXPIRY_ALERT_DAYS, new Settings.Scope(legalEntityId, null), today);
        if (v instanceof List<?> l) {
            l.stream().filter(Number.class::isInstance).map(x -> ((Number) x).intValue()).filter(x -> x >= 0)
                    .forEach(out::add);
        }
        return out;
    }

    /** The expiry alert of this contract and offset was already published. */
    private boolean alreadyPublished(Row r, int offset) {
        return db.jdbc().sql("""
                        select exists (select 1 from hr.outbox_event
                                        where tenant_id = :t and event_type = 'people.ContractExpiring'
                                          and employment_id = :e and payload ->> 'contractId' = :c
                                          and not (payload ->> 'autoConversionDue')::boolean
                                          and (payload ->> 'daysBefore')::int = :o)""")
                .param("t", tenant()).param("e", r.employmentId()).param("c", r.contractId().toString())
                .param("o", offset)
                .query(Boolean.class).single();
    }

    /**
     * Records the first day the contract counts as converted to indefinite (BR-PPL-054); false when another run set it
     * first. A system update: the version is bumped so that pending edits of the contract reload.
     */
    private boolean flagAutoConversion(Row r, LocalDate dueSince) {
        return db.jdbc().sql("""
                        update hr.contract set auto_conversion_due_since = :since, version = version + 1
                         where tenant_id = :t and id = :id and auto_conversion_due_since is null""")
                .param("since", dueSince).param("t", tenant()).param("id", r.contractId())
                .update() == 1;
    }

    // ------------------------------------------------------------------------------- probation (BR-PPL-052)

    private int probationAlerts(LocalDate today) {
        List<Row> rows = db.jdbc().sql("select c.id, c.employment_id, e.person_id, e.legal_entity_id, e.employee_no, "
                        + "c.contract_no, c.contract_type_code, " + ContractExpiryService.PROBATION_END + ", c.status "
                        + """
                          from hr.contract c
                          join hr.employment e on e.tenant_id = c.tenant_id and e.id = c.employment_id
                         where c.tenant_id = :t and c.status = 'ACTIVE' and e.status in ('ACTIVE','PRE_HIRE')
                           and""" + " " + ContractExpiryService.PROBATION_END + " >= :d and "
                        + ContractExpiryService.NO_RESULT)
                .param("t", tenant()).param("d", today)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getObject(8, LocalDate.class), rs.getString(9)))
                .list();
        Map<UUID, Long> leadByLe = new HashMap<>();
        int sent = 0;
        for (Row r : rows) {
            long lead = leadByLe.computeIfAbsent(r.legalEntityId(), le -> settings.longValue(
                    ContractKeys.SETTING_PROBATION_ALERT_DAYS, new Settings.Scope(le, null), today));
            long left = ChronoUnit.DAYS.between(today, r.endDate());
            if (left <= lead) {
                notify(ContractNotificationTemplates.PROBATION_ENDING, r, today, Map.of("employeeNo", r.employeeNo(),
                        "contractNo", r.contractNo(), "endDate", VN.format(r.endDate()), "daysLeft", left),
                        "probation-ending:" + r.contractId() + ":" + today);
                sent++;
            }
        }
        return sent;
    }

    // ------------------------------------------------------------------------------ return to work (BR-PPL-056)

    private int returnAlerts(LocalDate today) {
        int sent = 0;
        for (SuspensionView s : suspensions.returnsDue(today)) {
            Row r = db.jdbc().sql("""
                            select e.person_id, e.legal_entity_id, e.employee_no from hr.employment e
                             where e.tenant_id = :t and e.id = :id""")
                    .param("t", tenant()).param("id", s.employmentId())
                    .query((rs, n) -> new Row(s.contractId(), s.employmentId(), rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), rs.getString(3), s.contractNo(), null,
                            s.plannedEndDate(), s.status()))
                    .single();
            Map<String, Object> payload = new HashMap<>(Map.of("employeeNo", r.employeeNo(), "contractNo",
                    r.contractNo(), "endDate", VN.format(s.plannedEndDate())));
            payload.put("dueDate", s.returnDueDate() == null ? "" : VN.format(s.returnDueDate()));
            notify(ContractNotificationTemplates.SUSPENSION_RETURN_DUE, r, today, payload,
                    "suspension-return:" + s.id() + ":" + today);
            sent++;
        }
        return sent;
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private void notify(String event, Row r, LocalDate today, Map<String, Object> payload, String dedupeKey) {
        Optional<AuthorizationTarget.Employee> target = access.target(r.employmentId(), today);
        if (target.isEmpty()) {
            return;
        }
        List<Audience> audiences = new ArrayList<>();
        audiences.add(Audience.permission(PeoplePermissions.CONTRACT_UPDATE, target.get()));
        audiences.add(Audience.permission(PeoplePermissions.CONTRACT_VIEW, target.get()));
        notifications.send(new Notifications.SendRequest(event, "hr", new Recipients(null, audiences), payload,
                "hr.contract", r.contractId(), "/people/contracts/" + r.contractId(), dedupeKey, null));
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
