package vn.yourhrm.people.internal.application.onboarding;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.application.separation.SeparationService;
import vn.yourhrm.people.internal.application.separation.SeparationService.ServicePeriodData;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;

/**
 * {@code OB_EMP_SERVICE_HISTORY} (US-PPL-092; BR-PPL-074): prior service with the same legal entity, recognised prior
 * service and periods for which severance / job-loss allowance was already paid, needed for the severance of people
 * who leave after go-live. Loaded as closed records, no events.
 *
 * <p><b>Design</b> ({@code hr.separation_service_period.separation_id} is NOT NULL, so a period needs a separation):
 * <ul>
 *   <li>WORKED: an earlier employment of the person in the legal entity, created TERMINATED ({@code source =
 *       OPENING_LOAD}, employee number {@code prior_employee_no} or {@code <employee no>-H<yyyyMMdd>}) with a COMPLETED
 *       separation (ground {@value OnboardingKeys#HISTORY_GROUND}, no severance). The separation stream's
 *       {@code refreshDerivedPeriods} then derives it as WORKED for the next separation of the person in that legal
 *       entity, as for any earlier employment.</li>
 *   <li>PAID_SEVERANCE / PAID_JOB_LOSS / RECOGNISED_PRIOR: added through
 *       {@code SeparationService.addServicePeriodsForInitialLoad} to the COMPLETED separation of the earlier employment
 *       that contains the period (RECOGNISED_PRIOR: the first earlier employment ending on or after the period). PAID
 *       periods of earlier COMPLETED separations are carried into the next separation by the separation stream;
 *       RECOGNISED_PRIOR periods are not (reported gap) — the employment's {@code seniority_date} also reflects
 *       recognised service.</li>
 * </ul>
 * In one file the WORKED row must come before the periods it holds.
 */
@Component
class ServiceHistoryImport extends OpeningBalanceImport {

    static final Set<String> KINDS = Set.of("WORKED", "RECOGNISED_PRIOR", "PAID_SEVERANCE", "PAID_JOB_LOSS");

    private final EmploymentStore store;
    private final SeparationService separations;

    ServiceHistoryImport(OnboardingSupport support, EmploymentStore store, SeparationService separations) {
        super(support);
        this.store = store;
        this.separations = separations;
    }

    @Override
    public Definition definition() {
        return definition(OnboardingKeys.SERVICE_HISTORY, "Số dư đầu kỳ - Lịch sử thời gian làm việc",
                "Opening balance - service history",
                List.of(employeeNo(),
                        text("period_kind", "Loại giai đoạn", "Period kind", true,
                                "WORKED (đã làm việc tại pháp nhân), RECOGNISED_PRIOR (được công nhận), PAID_SEVERANCE "
                                        + "(đã chi trợ cấp thôi việc), PAID_JOB_LOSS (đã chi trợ cấp mất việc làm)",
                                "WORKED, RECOGNISED_PRIOR, PAID_SEVERANCE, PAID_JOB_LOSS"),
                        date("period_from", "Từ ngày", "From", true),
                        date("period_to", "Đến ngày (bao gồm)", "To (inclusive)", true),
                        text("prior_employee_no", "Mã nhân viên giai đoạn trước", "Earlier employee number", false),
                        text("note", "Ghi chú", "Note", false)),
                List.of("employee_no", "period_kind", "period_from"), List.of(OnboardingKeys.MASTER), List.of());
    }

    @Override
    protected List<RowMessage> precheck(ImportContext ctx, ImportRow row, Map<String, Object> state) {
        String kind = upper(row, "period_kind");
        if (!KINDS.contains(kind)) {
            return List.of(OnboardingSupport.err("period_kind", "invalid-period-kind", "Loại giai đoạn không hợp lệ",
                    "Period kind must be one of " + KINDS));
        }
        if (row.date("period_to").isBefore(row.date("period_from"))) {
            return List.of(OnboardingSupport.err("period_to", "invalid-period", "Đến ngày trước từ ngày",
                    "The end is before the start"));
        }
        return List.of();
    }

    private record Holder(UUID employmentId, UUID separationId, LocalDate from, LocalDate to) {
    }

    @Override
    protected Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun) {
        OnboardingSupport.Emp emp = s.requireEmployment(row.text("employee_no"));
        s.requireUnlocked(emp.legalEntityId());
        String kind = upper(row, "period_kind");
        LocalDate from = row.date("period_from");
        LocalDate to = row.date("period_to");
        if (!to.isBefore(emp.hireDate())) {
            throw ApiException.unprocessable("history-not-before-hire",
                    "Service history must end before the hire date " + emp.hireDate() + " of the current employment");
        }
        @SuppressWarnings("unchecked")
        List<Holder> rehearsed = (List<Holder>) state.computeIfAbsent("worked:" + emp.personId() + ":"
                + emp.legalEntityId(), k -> new ArrayList<Holder>());
        if ("WORKED".equals(kind)) {
            return worked(row, emp, from, to, dryRun, rehearsed);
        }
        List<Holder> holders = holders(emp);
        Optional<Holder> holder = holders.stream()
                .filter(h -> "RECOGNISED_PRIOR".equals(kind) ? !h.to().isBefore(to)
                        : !from.isBefore(h.from()) && !to.isAfter(h.to()))
                .findFirst();
        if (holder.isEmpty()) {
            boolean inFile = rehearsed.stream().anyMatch(h -> "RECOGNISED_PRIOR".equals(kind) ? !h.to().isBefore(to)
                    : !from.isBefore(h.from()) && !to.isAfter(h.to()));
            if (dryRun && inFile) {
                return Written.created("hr.separation_service_period", null, List.of());
            }
            throw ApiException.unprocessable("history-holder-missing", "No earlier employment of the person in this "
                    + "legal entity " + ("RECOGNISED_PRIOR".equals(kind) ? "ends after" : "contains") + " the period: "
                    + "load it first as a WORKED row");
        }
        var j = s.db().jdbc();
        Optional<UUID> same = j.sql("""
                        select id from hr.separation_service_period where tenant_id = :t and separation_id = :s
                           and period_kind = :k and period_from = :f""")
                .param("t", OnboardingSupport.tenant()).param("s", holder.get().separationId()).param("k", kind)
                .param("f", from).query(UUID.class).optional();
        if (same.isPresent()) {
            return Written.updated("hr.separation_service_period", same.get(), List.of());
        }
        var periods = separations.addServicePeriodsForInitialLoad(holder.get().separationId(), List.of(
                new ServicePeriodData(kind, from, to, holder.get().employmentId(), row.text("note"))));
        UUID id = periods.stream().filter(p -> p.periodKind().equals(kind) && p.periodFrom().equals(from))
                .map(p -> p.id()).findFirst().orElseThrow();
        return Written.created("hr.separation_service_period", id, List.of());
    }

    private Written worked(ImportRow row, OnboardingSupport.Emp emp, LocalDate from, LocalDate to, boolean dryRun,
            List<Holder> rehearsed) {
        Optional<Holder> same = holders(emp).stream().filter(h -> h.from().equals(from)).findFirst();
        if (same.isPresent()) {
            return Written.updated("hr.employment", same.get().employmentId(), List.of());
        }
        String no = row.text("prior_employee_no") != null ? row.text("prior_employee_no").strip()
                : emp.employeeNo() + "-H" + from.format(DateTimeFormatter.BASIC_ISO_DATE);
        boolean earlier = !store.ofPerson(emp.personId()).stream()
                .filter(e -> !"CANCELLED".equals(e.status()) && e.hireDate().isBefore(from)).toList().isEmpty();
        UUID id = Ids.newId();
        var j = s.db().jdbc();
        UUID t = OnboardingSupport.tenant();
        try {
            // inserted closed at once: an open period would overlap the current employment (employment_person_le_ex)
            j.sql("""
                            insert into hr.employment (tenant_id, id, person_id, legal_entity_id, employee_no, hire_type,
                                   hire_date, original_hire_date, seniority_date, end_date, last_working_date, status,
                                   worker_category_code, is_rehire_eligible, source, created_by, updated_by)
                            values (:t, :id, :p, :le, :no, :ht, :from, :from, :from, :to, :to, 'TERMINATED',
                                    'EMPLOYEE', true, 'OPENING_LOAD', :a, :a)""")
                    .param("t", t).param("id", id).param("p", emp.personId()).param("le", emp.legalEntityId())
                    .param("no", no).param("ht", earlier ? "REHIRE" : "NEW").param("from", from).param("to", to)
                    .param("a", OnboardingSupport.actor()).update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            String m = String.valueOf(ex.getMostSpecificCause().getMessage());
            if (m.contains("employment_no_uq")) {
                throw ApiException.conflict("employee-no-taken", "Employee number " + no + " is already used");
            }
            if (m.contains("employment_person_le_ex")) {
                throw ApiException.unprocessable("history-overlap", "The period overlaps another employment of the "
                        + "person in this legal entity");
            }
            throw ex;
        }
        j.sql("""
                        insert into hr.separation (tenant_id, id, employment_id, separation_type, initiated_by,
                               legal_ground_code, last_working_date, termination_date, severance_type, status,
                               created_by, updated_by)
                        values (:t, :id, :e, 'OTHER', 'EMPLOYER', :g, :to, :to, 'NONE', 'COMPLETED', :a, :a)""")
                .param("t", t).param("id", Ids.newId()).param("e", id).param("g", OnboardingKeys.HISTORY_GROUND)
                .param("to", to).param("a", OnboardingSupport.actor()).update();
        if (dryRun) {
            rehearsed.add(new Holder(id, null, from, to));
        }
        return Written.created("hr.employment", id, List.of());
    }

    /** Earlier employments of the person in the legal entity with their COMPLETED separation, by start. */
    private List<Holder> holders(OnboardingSupport.Emp emp) {
        return s.db().jdbc().sql("""
                        select e.id, s.id, e.hire_date, e.end_date from hr.employment e
                          join hr.separation s on s.tenant_id = e.tenant_id and s.employment_id = e.id
                               and s.status = 'COMPLETED'
                         where e.tenant_id = :t and e.person_id = :p and e.legal_entity_id = :le and e.id <> :cur
                           and e.status = 'TERMINATED' and e.end_date is not null
                         order by e.hire_date""")
                .param("t", OnboardingSupport.tenant()).param("p", emp.personId()).param("le", emp.legalEntityId())
                .param("cur", emp.id())
                .query((rs, n) -> new Holder(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class)))
                .list();
    }

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        return s.write(() -> s.locked(ctx.legalEntityId()) ? List.of("Opening balances are locked (go-live)")
                : new ArrayList<String>());
    }

    /**
     * Deletes the periods the job created; the earlier employments and their separations are CANCELLED (no DELETE
     * grant on {@code hr.employment} / {@code hr.separation}).
     */
    @Override
    public void undo(ImportContext ctx, List<CommittedRow> rows) {
        s.write(() -> {
            var j = s.db().jdbc();
            UUID t = OnboardingSupport.tenant();
            for (CommittedRow r : rows) {
                if (!"CREATED".equals(r.action())) {
                    continue;
                }
                if ("hr.separation_service_period".equals(r.targetEntity())) {
                    j.sql("delete from hr.separation_service_period where tenant_id = :t and id = :id")
                            .param("t", t).param("id", r.targetId()).update();
                } else {
                    j.sql("""
                                    delete from hr.separation_service_period where tenant_id = :t and separation_id in
                                           (select id from hr.separation where tenant_id = :t and employment_id = :e)""")
                            .param("t", t).param("e", r.targetId()).update();
                    j.sql("update hr.separation set status = 'CANCELLED', version = version + 1 "
                                    + "where tenant_id = :t and employment_id = :e")
                            .param("t", t).param("e", r.targetId()).update();
                    j.sql("update hr.employment set status = 'CANCELLED', version = version + 1 "
                                    + "where tenant_id = :t and id = :e")
                            .param("t", t).param("e", r.targetId()).update();
                }
            }
            return null;
        });
    }
}
