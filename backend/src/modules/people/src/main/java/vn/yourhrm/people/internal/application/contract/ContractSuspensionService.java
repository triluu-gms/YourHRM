package vn.yourhrm.people.internal.application.contract;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.ContractEvents;
import vn.yourhrm.people.api.ContractEvents.ContractChanged;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.application.contract.ContractRecords.ContractView;
import vn.yourhrm.people.internal.application.contract.ContractRecords.Employment;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.legal.LegalParameters;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Contract suspension (tạm hoãn, BR-PPL-056, US-PPL-054; Labour Code Art. 30-31 [verify-legal]) for a statutory
 * reason, issued by a CONTRACT_SUSPENSION decision. From the first suspended day the suspension is ACTIVE, the
 * contract SUSPENDED and the employment SUSPENDED, and {@code ContractChanged} SUSPENDED stops pay, SI and attendance
 * downstream; recording the return to work ends it ({@code ContractChanged} RESUMED, employment ACTIVE). The employee
 * must return within {@code contract.suspension.return_window_days} days after the suspension ends: the daily
 * monitor reminds HR until the return is recorded.
 *
 * <p>Dates: the API speaks of an INCLUSIVE planned last day; {@code hr.contract_suspension.valid_to} is EXCLUSIVE.
 */
@Service
public class ContractSuspensionService {

    /** Statutory reasons (code list of the DDL comment, Art. 30). */
    public static final Set<String> REASONS = Set.of("MILITARY_SERVICE", "DETENTION", "MATERNITY_EXTENDED",
            "ELECTED_OFFICE", "AGREEMENT", "OTHER");

    /** {@code plannedEndDate}: last suspended day (inclusive); {@code returnDueDate}: latest legal return day. */
    public record SuspensionView(UUID id, UUID contractId, UUID employmentId, String contractNo, String reasonCode,
            LocalDate validFrom, LocalDate plannedEndDate, LocalDate returnDate, LocalDate returnDueDate,
            UUID decisionId, String decisionNo, String status, int version) {
    }

    private final HrDb db;
    private final ContractRecords records;
    private final ContractService contracts;
    private final DecisionService decisions;
    private final PeopleLegalParameters legal;
    private final EmployeeAccess access;
    private final HrOutbox outbox;
    private final BusinessClock clock;

    public ContractSuspensionService(HrDb db, ContractRecords records, ContractService contracts,
            DecisionService decisions, PeopleLegalParameters legal, EmployeeAccess access, HrOutbox outbox,
            BusinessClock clock) {
        this.db = db;
        this.records = records;
        this.contracts = contracts;
        this.decisions = decisions;
        this.legal = legal;
        this.access = access;
        this.outbox = outbox;
        this.clock = clock;
    }

    @HrTransactional(readOnly = true)
    public List<SuspensionView> list(UUID employmentId) {
        access.require(PeoplePermissions.CONTRACT_VIEW, employmentId, clock.today());
        return query(" where s.tenant_id = :t and s.employment_id = :e order by s.valid_from", "e", employmentId);
    }

    @HrTransactional(readOnly = true)
    public SuspensionView get(UUID suspensionId) {
        SuspensionView s = find(suspensionId);
        access.require(PeoplePermissions.CONTRACT_VIEW, s.employmentId(), clock.today());
        return s;
    }

    /**
     * Suspends the contract from {@code validFrom} to {@code plannedEndDate} (inclusive; null = open, e.g. detention)
     * with a CONTRACT_SUSPENSION decision. Starts at once when {@code validFrom} is today or earlier, else on that day
     * through the daily monitor.
     */
    @HrTransactional
    public SuspensionView create(UUID contractId, String reasonCode, LocalDate validFrom, LocalDate plannedEndDate,
            String signatoryName, String signatoryTitle) {
        ContractView c = records.require(contractId);
        contracts.requireScope(PeoplePermissions.CONTRACT_UPDATE, c);
        if (!REASONS.contains(reasonCode)) {
            throw ApiException.unprocessable("invalid-suspension-reason", "Unknown suspension reason " + reasonCode,
                    Map.of("allowed", REASONS.stream().sorted().toList()));
        }
        if (!c.type().labourContract() || !"ACTIVE".equals(c.status())) {
            throw ApiException.unprocessable("contract-not-suspendable", "Only a signed labour contract in force can "
                    + "be suspended; " + c.contractNo() + " is " + c.contractTypeCode() + " / " + c.status());
        }
        if (validFrom == null || validFrom.isBefore(c.startDate())
                || (c.endDate() != null && validFrom.isAfter(c.endDate()))) {
            throw ApiException.unprocessable("suspension-outside-contract",
                    "The suspension must start within the contract term");
        }
        if (plannedEndDate != null && plannedEndDate.isBefore(validFrom)) {
            throw ApiException.unprocessable("invalid-period", "The planned end is before the start");
        }
        Employment emp = records.requireEmployment(c.employmentId());
        if (!"ACTIVE".equals(emp.status())) {
            throw ApiException.unprocessable("employment-not-active", "The employment is " + emp.status());
        }
        DecisionService.Decision decision = decisions.issue(new DecisionService.Issue(c.legalEntityId(),
                "CONTRACT_SUSPENSION", c.employmentId(), validFrom, signatoryName, signatoryTitle, null));
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into hr.contract_suspension (tenant_id, id, employment_id, contract_id, reason_code,
                                   valid_from, valid_to, decision_id, status, created_by, updated_by)
                            values (:t, :id, :e, :c, :r, :from, :to, :d, 'APPROVED', :actor, :actor)""")
                    .param("t", tenant()).param("id", id).param("e", c.employmentId()).param("c", c.id())
                    .param("r", reasonCode).param("from", validFrom)
                    .param("to", plannedEndDate == null ? null : plannedEndDate.plusDays(1))
                    .param("d", decision.id()).param("actor", actor())
                    .update();
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.unprocessable("suspension-overlap", "The period overlaps another suspension");
        }
        if (!validFrom.isAfter(clock.today())) {
            start(id);
        }
        return find(id);
    }

    /** Records the return to work (US-PPL-054): ACTIVE → ENDED, contract and employment ACTIVE again. */
    @HrTransactional
    public SuspensionView resume(UUID suspensionId, LocalDate returnDate, int expectedVersion) {
        SuspensionView s = find(suspensionId);
        access.require(PeoplePermissions.CONTRACT_UPDATE, s.employmentId(), clock.today());
        if (!"ACTIVE".equals(s.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Suspension is " + s.status());
        }
        if (s.version() != expectedVersion) {
            throw stale(s);
        }
        if (returnDate == null || !returnDate.isAfter(s.validFrom()) || returnDate.isAfter(clock.today())) {
            throw ApiException.unprocessable("invalid-return-date",
                    "The return date lies after the first suspended day and not in the future");
        }
        int updated = db.jdbc().sql("""
                        update hr.contract_suspension
                           set status = 'ENDED', return_date = :r,
                               valid_to = case when valid_to is null or valid_to > :r then :r else valid_to end,
                               version = version + 1, updated_by = :actor
                         where tenant_id = :t and id = :id and version = :v and status = 'ACTIVE'""")
                .param("r", returnDate).param("actor", actor()).param("t", tenant()).param("id", suspensionId)
                .param("v", expectedVersion).update();
        if (updated == 0) {
            throw stale(s);
        }
        db.jdbc().sql("""
                        update hr.contract set status = 'ACTIVE', version = version + 1, updated_by = :actor
                         where tenant_id = :t and id = :c and status = 'SUSPENDED'""")
                .param("actor", actor()).param("t", tenant()).param("c", s.contractId()).update();
        setEmploymentStatus(s.employmentId(), "SUSPENDED", "ACTIVE");
        publish(records.require(s.contractId()), ContractEvents.RESUMED, find(suspensionId), returnDate);
        return find(suspensionId);
    }

    /** Withdraws a suspension that has not started yet; its decision is cancelled (the number is kept). */
    @HrTransactional
    public SuspensionView cancel(UUID suspensionId, int expectedVersion) {
        SuspensionView s = find(suspensionId);
        access.require(PeoplePermissions.CONTRACT_UPDATE, s.employmentId(), clock.today());
        if (!"APPROVED".equals(s.status()) && !"DRAFT".equals(s.status())) {
            throw ApiException.unprocessable("invalid-status-transition",
                    "Only a suspension that has not started can be cancelled; record the return instead");
        }
        int updated = db.jdbc().sql("""
                        update hr.contract_suspension set status = 'CANCELLED', version = version + 1, updated_by = :actor
                         where tenant_id = :t and id = :id and version = :v and status in ('DRAFT','APPROVED')""")
                .param("actor", actor()).param("t", tenant()).param("id", suspensionId).param("v", expectedVersion)
                .update();
        if (updated == 0) {
            throw stale(s);
        }
        if (s.decisionId() != null) {
            decisions.find(s.decisionId()).filter(d -> !"CANCELLED".equals(d.status()))
                    .ifPresent(d -> decisions.cancel(d.id(), d.version()));
        }
        return find(suspensionId);
    }

    // --------------------------------------------------------------------------------------------- daily monitor

    /** Starts the APPROVED suspensions whose first day is {@code today} or earlier; returns how many. */
    @HrTransactional
    public int startDue(LocalDate today) {
        List<UUID> due = db.jdbc().sql("""
                        select id from hr.contract_suspension
                         where tenant_id = :t and status = 'APPROVED' and valid_from <= :d order by valid_from""")
                .param("t", tenant()).param("d", today).query(UUID.class).list();
        due.forEach(this::start);
        return due.size();
    }

    /** ACTIVE suspensions whose planned end has passed without a recorded return (return-to-work reminders). */
    @HrTransactional(readOnly = true)
    public List<SuspensionView> returnsDue(LocalDate today) {
        return query(" where s.tenant_id = :t and s.status = 'ACTIVE' and s.return_date is null and s.valid_to <= :d"
                + " order by s.valid_to", "d", today);
    }

    private void start(UUID suspensionId) {
        SuspensionView s = find(suspensionId);
        db.jdbc().sql("""
                        update hr.contract_suspension set status = 'ACTIVE', version = version + 1, updated_by = :actor
                         where tenant_id = :t and id = :id and status = 'APPROVED'""")
                .param("actor", actor()).param("t", tenant()).param("id", suspensionId).update();
        db.jdbc().sql("""
                        update hr.contract set status = 'SUSPENDED', version = version + 1, updated_by = :actor
                         where tenant_id = :t and id = :c and status = 'ACTIVE'""")
                .param("actor", actor()).param("t", tenant()).param("c", s.contractId()).update();
        setEmploymentStatus(s.employmentId(), "ACTIVE", "SUSPENDED");
        publish(records.require(s.contractId()), ContractEvents.SUSPENDED, find(suspensionId), s.validFrom());
    }

    /** The only write of this stream to {@code hr.employment}: ACTIVE ↔ SUSPENDED (BR-PPL-056). */
    private void setEmploymentStatus(UUID employmentId, String from, String to) {
        db.jdbc().sql("""
                        update hr.employment set status = :to, version = version + 1, updated_by = :actor
                         where tenant_id = :t and id = :e and status = :from""")
                .param("to", to).param("from", from).param("actor", actor()).param("t", tenant())
                .param("e", employmentId).update();
    }

    private void publish(ContractView c, String subtype, SuspensionView s, LocalDate effective) {
        outbox.publish(new ContractChanged(tenant(), c.legalEntityId(), c.personId(), c.employmentId(), effective,
                subtype, c.id(), c.contractNo(), c.contractTypeCode(), c.startDate(), c.endDate(), c.definiteSeq(),
                c.previousContractId(), null, null, null, List.of(), s.id(), s.reasonCode(), s.plannedEndDate(),
                false));
    }

    private SuspensionView find(UUID id) {
        return query(" where s.tenant_id = :t and s.id = :id", "id", id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Suspension", id));
    }

    private List<SuspensionView> query(String where, String param, Object value) {
        return db.jdbc().sql("""
                        select s.id, s.contract_id, s.employment_id, c.contract_no, s.reason_code, s.valid_from,
                               s.valid_to, s.return_date, s.decision_id, d.decision_no, s.status, s.version,
                               e.legal_entity_id
                          from hr.contract_suspension s
                          join hr.contract c on c.tenant_id = s.tenant_id and c.id = s.contract_id
                          join hr.employment e on e.tenant_id = s.tenant_id and e.id = s.employment_id
                          left join hr.decision d on d.tenant_id = s.tenant_id and d.id = s.decision_id""" + where)
                .param("t", tenant()).param(param, value)
                .query((rs, n) -> {
                    LocalDate validTo = rs.getObject(7, LocalDate.class);
                    LocalDate due = null;
                    if (validTo != null) {
                        // display only: a missing parameter leaves the due date empty instead of failing the read
                        due = legal.find(ContractKeys.SUSPENSION_RETURN_DAYS,
                                        new LegalParameters.Scope(rs.getObject(13, UUID.class), null), validTo)
                                .map(v -> validTo.plusDays(v.asInt() - 1L)).orElse(null);
                    }
                    return new SuspensionView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                            rs.getObject(6, LocalDate.class), validTo == null ? null : validTo.minusDays(1),
                            rs.getObject(8, LocalDate.class), due, rs.getObject(9, UUID.class), rs.getString(10),
                            rs.getString(11), rs.getInt(12));
                })
                .list();
    }

    private static ApiException stale(SuspensionView s) {
        return ApiException.conflict("stale-version", "Suspension " + s.id() + " was changed (version " + s.version()
                + ", status " + s.status() + "); reload and retry");
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }
}
