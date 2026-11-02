package vn.yourhrm.people.internal.application.contract;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The expiry list (BR-PPL-054, US-PPL-051/052): contracts ending within N days, and definite contracts that ended
 * without a following contract while the employee keeps working (with the auto-conversion flag once the legal number
 * of days has passed), plus the probations ending without a recorded result. Filtered by the caller's data scope of
 * {@code hr.contract.view}. The auto-conversion flag is derived (the DDL has no column for it).
 */
@Service
public class ContractExpiryService {

    /**
     * @param autoConversionDue    worked more than {@code contract.definite.auto_convert_after_days} days past the end
     *                             without a new contract (null when the legal parameter is missing)
     * @param nextMustBeIndefinite the consecutive-definite limit is reached: a renewal needs a legal exception
     */
    public record ExpiringContract(UUID contractId, UUID employmentId, UUID legalEntityId, String employeeNo,
            String contractNo, String contractTypeCode, Integer definiteSeq, LocalDate endDate, String status,
            long daysLeft, UUID followingContractId, Boolean autoConversionDue, Boolean nextMustBeIndefinite) {
    }

    public record EndingProbation(UUID contractId, UUID employmentId, UUID legalEntityId, String employeeNo,
            String contractNo, String contractTypeCode, LocalDate probationEndDate, long daysLeft) {
    }

    static final String FOLLOWING = """
            (select s.id from hr.contract s
              where s.tenant_id = c.tenant_id and s.employment_id = c.employment_id
                and s.status not in ('CANCELLED','SUPERSEDED') and s.id <> c.id
                and (s.previous_contract_id = c.id or s.start_date > c.end_date)
              order by s.start_date limit 1)""";

    static final String PROBATION_END = """
            (case when c.contract_type_code = 'PROBATION' then c.end_date
                  when coalesce(c.probation_days, 0) > 0 then c.start_date + (c.probation_days - 1) end)""";

    static final String NO_RESULT = """
            not exists (select 1 from hr.outbox_event o
                         where o.tenant_id = c.tenant_id and o.event_type = 'people.ProbationCompleted'
                           and o.employment_id = c.employment_id and o.payload ->> 'contractId' = c.id::text)""";

    private final HrDb db;
    private final EmployeeAccess access;
    private final PeopleLegalParameters legal;
    private final BusinessClock clock;

    public ContractExpiryService(HrDb db, EmployeeAccess access, PeopleLegalParameters legal, BusinessClock clock) {
        this.db = db;
        this.access = access;
        this.legal = legal;
        this.clock = clock;
    }

    /** Contracts ending in [asOf, asOf + withinDays] or ended without a following contract (employee still employed). */
    @HrTransactional(readOnly = true)
    public List<ExpiringContract> expiring(LocalDate asOf, int withinDays, UUID legalEntityId) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        var scope = access.scope(PeoplePermissions.CONTRACT_VIEW, d, "s");
        Map<UUID, Optional<Integer>> convertDays = new HashMap<>();
        Map<UUID, Optional<Integer>> maxConsecutive = new HashMap<>();
        return db.jdbc().sql("select c.id, c.employment_id, e.legal_entity_id, e.employee_no, c.contract_no, "
                        + "c.contract_type_code, c.definite_seq, c.end_date, c.status, " + FOLLOWING + " as following, "
                        + "e.status as employment_status from " + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN + """
                         join hr.contract c on c.tenant_id = e.tenant_id and c.employment_id = e.id
                        where e.tenant_id = :t and c.end_date is not null and c.contract_type_code <> 'PROBATION'
                          and c.status in ('ACTIVE','SUSPENDED','EXPIRED') and c.end_date <= :until
                          and (c.end_date >= :asOf
                               or (c.status = 'EXPIRED' and e.status in ('ACTIVE','SUSPENDED')
                                   and""" + " " + FOLLOWING + """
                                    is null))
                          and (cast(:le as uuid) is null or e.legal_entity_id = :le)
                          and""" + " " + scope.sql() + " order by c.end_date, c.contract_no")
                .param("t", tenant()).param("asOf", d).param("until", d.plusDays(withinDays)).param("le", legalEntityId)
                .params(scope.params())
                .query((rs, n) -> {
                    UUID le = rs.getObject(3, UUID.class);
                    String type = rs.getString(6);
                    Integer seq = rs.getObject(7, Integer.class);
                    LocalDate end = rs.getObject(8, LocalDate.class);
                    UUID following = rs.getObject(10, UUID.class);
                    boolean definite = "DEFINITE".equals(type) || "SEASONAL".equals(type);
                    boolean employed = List.of("ACTIVE", "SUSPENDED").contains(rs.getString(11));
                    Optional<Integer> n1 = convertDays.computeIfAbsent(le, k -> param(ContractKeys.DEFINITE_AUTO_CONVERT_DAYS, k, d));
                    Optional<Integer> n2 = maxConsecutive.computeIfAbsent(le, k -> param(ContractKeys.DEFINITE_MAX_CONSECUTIVE, k, d));
                    Boolean auto = !definite ? Boolean.FALSE : n1.map(days -> following == null && employed
                            && ChronoUnit.DAYS.between(end, d) > days).orElse(null);
                    Boolean indefiniteNext = !definite || seq == null ? Boolean.FALSE
                            : n2.map(max -> seq >= max).orElse(null);
                    return new ExpiringContract(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), le,
                            rs.getString(4), rs.getString(5), type, seq, end, rs.getString(9),
                            ChronoUnit.DAYS.between(d, end), following, auto, indefiniteNext);
                })
                .list();
    }

    /** Signed contracts whose probation ends in [asOf, asOf + withinDays] with no result recorded yet (US-PPL-051). */
    @HrTransactional(readOnly = true)
    public List<EndingProbation> probationsEnding(LocalDate asOf, int withinDays, UUID legalEntityId) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        var scope = access.scope(PeoplePermissions.CONTRACT_VIEW, d, "s");
        return db.jdbc().sql("select c.id, c.employment_id, e.legal_entity_id, e.employee_no, c.contract_no, "
                        + "c.contract_type_code, " + PROBATION_END + " as probation_end from "
                        + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN + """
                         join hr.contract c on c.tenant_id = e.tenant_id and c.employment_id = e.id
                        where e.tenant_id = :t and c.status = 'ACTIVE'
                          and""" + " " + PROBATION_END + " between :asOf and :until and " + NO_RESULT + """
                          and (cast(:le as uuid) is null or e.legal_entity_id = :le)
                          and""" + " " + scope.sql() + " order by probation_end, c.contract_no")
                .param("t", tenant()).param("asOf", d).param("until", d.plusDays(withinDays)).param("le", legalEntityId)
                .params(scope.params())
                .query((rs, n) -> new EndingProbation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getObject(7, LocalDate.class),
                        ChronoUnit.DAYS.between(d, rs.getObject(7, LocalDate.class))))
                .list();
    }

    private Optional<Integer> param(String key, UUID legalEntityId, LocalDate on) {
        return legal.find(key, new Scope(legalEntityId, null), on).map(v -> v.asInt());
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
