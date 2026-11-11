package vn.yourhrm.people.internal.application.employment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.application.employment.InsuranceParticipationService.ParticipationView;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore.Row;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Concurrent-employment exception report (US-PPL-025, BR-PPL-004, RK7) per legal entity and date, over the persons
 * with an employment of that legal entity on the date (in the caller's scope of {@code hr.employee.view}):
 * <ul>
 *   <li>NO_PRIMARY: 2+ live employments and no PIT primary designation covers the day;</li>
 *   <li>OVERLAPPING_PRIMARY: more than one designation covers the day (prevented by {@code primary_employment_ex};
 *       kept as a safety net), PRIMARY_NOT_LIVE: the designated employment is not live that day;</li>
 *   <li>MISSING_INSURANCE_PARTICIPATION: a live employment of the legal entity has no row for an insurance type;</li>
 *   <li>DUPLICATE_INSURANCE_PARTICIPATION / NO_INSURANCE_PAYER: under a single-payer rule more than one / none of the
 *       person's live employments participates (and none is exempt);</li>
 *   <li>INSURANCE_RULE_MISSING: {@code insurance.multi_contract_rule} is not defined for the date.</li>
 * </ul>
 * Each line carries the API link of the screen that fixes it.
 */
@Service
public class ConcurrentEmploymentReport {

    public record ExceptionLine(String code, UUID personId, String fullName, UUID employmentId, String employeeNo,
            String insuranceType, String detail, String link) {
    }

    public record Report(UUID legalEntityId, LocalDate asOf, int personsChecked, List<ExceptionLine> exceptions) {
    }

    private final HrDb db;
    private final EmploymentStore employments;
    private final EmployeeAccess access;
    private final PrimaryEmploymentService primaries;
    private final InsuranceParticipationService insurance;
    private final PeopleLegalParameters legal;
    private final BusinessClock clock;

    public ConcurrentEmploymentReport(HrDb db, EmploymentStore employments, EmployeeAccess access,
            PrimaryEmploymentService primaries, InsuranceParticipationService insurance, PeopleLegalParameters legal,
            BusinessClock clock) {
        this.db = db;
        this.employments = employments;
        this.access = access;
        this.primaries = primaries;
        this.insurance = insurance;
        this.legal = legal;
        this.clock = clock;
    }

    @HrTransactional(readOnly = true)
    public Report run(UUID legalEntityId, LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        var scope = access.scope(PeoplePermissions.EMPLOYEE_VIEW, d, "s");
        List<Map<String, Object>> persons = db.jdbc().sql("select distinct e.person_id, p.full_name from "
                        + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN
                        + " join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id"
                        + " where e.tenant_id = :t and e.legal_entity_id = :le and e.status <> 'CANCELLED'"
                        + " and e.hire_date <= :asOf and (e.end_date is null or e.end_date >= :asOf) and "
                        + scope.sql() + " order by p.full_name")
                .param("t", TenantContext.current().tenantId()).param("le", legalEntityId).param("asOf", d)
                .params(scope.params()).query().listOfRows();
        Optional<Map<String, Object>> rule = legal.find(EmploymentKeys.INSURANCE_MULTI_CONTRACT_RULE, Scope.tenant(), d)
                .filter(v -> v.value() instanceof Map<?, ?>).map(v -> v.asMap());
        List<ExceptionLine> out = new ArrayList<>();
        if (rule.isEmpty()) {
            out.add(new ExceptionLine("INSURANCE_RULE_MISSING", null, null, null, null, null,
                    "Legal parameter " + EmploymentKeys.INSURANCE_MULTI_CONTRACT_RULE + " is not defined on " + d,
                    null));
        }
        for (Map<String, Object> p : persons) {
            UUID personId = (UUID) p.get("person_id");
            String name = (String) p.get("full_name");
            List<Row> live = employments.ofPerson(personId).stream().filter(e -> e.covers(d)).toList();
            checkPrimary(out, personId, name, live, d);
            if (rule.isPresent()) {
                checkInsurance(out, personId, name, live, legalEntityId, rule.get(), d);
            }
        }
        return new Report(legalEntityId, d, persons.size(), out);
    }

    private void checkPrimary(List<ExceptionLine> out, UUID personId, String name, List<Row> live, LocalDate d) {
        String link = "/api/v1/people/persons/" + personId + "/primary-employment";
        var covering = primaries.designations(personId).stream()
                .filter(r -> "ACTIVE".equals(r.status()) && PrimaryEmploymentService.covers(r, d)).toList();
        if (covering.size() > 1) {
            out.add(new ExceptionLine("OVERLAPPING_PRIMARY", personId, name, null, null, null,
                    covering.size() + " primary designations cover " + d, link));
        }
        for (var r : covering) {
            if (live.stream().noneMatch(e -> e.id().equals(r.employmentId()))) {
                out.add(new ExceptionLine("PRIMARY_NOT_LIVE", personId, name, r.employmentId(), r.employeeNo(), null,
                        "The designated primary employment is not live on " + d, link));
            }
        }
        if (live.size() >= 2 && covering.isEmpty()) {
            out.add(new ExceptionLine("NO_PRIMARY", personId, name, null, null, null, live.size()
                    + " live employments and no PIT primary designation on " + d, link));
        }
    }

    private void checkInsurance(List<ExceptionLine> out, UUID personId, String name, List<Row> live, UUID le,
            Map<String, Object> rule, LocalDate d) {
        for (String type : EmploymentKeys.INSURANCE_TYPES) {
            List<ParticipationView> rows = new ArrayList<>();
            for (Row e : live) {
                Optional<ParticipationView> row = insurance.on(e.id(), type, d);
                if (row.isEmpty() && e.legalEntityId().equals(le)) {
                    out.add(new ExceptionLine("MISSING_INSURANCE_PARTICIPATION", personId, name, e.id(),
                            e.employeeNo(), type, "No " + type + " participation row on " + d, link(e)));
                }
                row.ifPresent(rows::add);
            }
            Object r = rule.get(type);
            boolean single = r != null && !"EACH_EMPLOYER".equals(r.toString());
            if (!single || live.stream().noneMatch(e -> e.legalEntityId().equals(le))) {
                continue;
            }
            long payers = rows.stream().filter(ParticipationView::participating).count();
            boolean exempt = rows.stream().anyMatch(x -> x.exemptionReasonCode() != null);
            Row first = live.stream().filter(e -> e.legalEntityId().equals(le)).findFirst().orElseThrow();
            if (payers > 1) {
                out.add(new ExceptionLine("DUPLICATE_INSURANCE_PARTICIPATION", personId, name, first.id(),
                        first.employeeNo(), type, payers + " employments participate in " + type + " under rule " + r,
                        link(first)));
            } else if (payers == 0 && !exempt && rows.size() == live.size()) {
                out.add(new ExceptionLine("NO_INSURANCE_PAYER", personId, name, first.id(), first.employeeNo(), type,
                        "No employment participates in " + type + " under rule " + r, link(first)));
            }
        }
    }

    private static String link(Row e) {
        return "/api/v1/people/employments/" + e.id() + "/insurance-participation";
    }
}
