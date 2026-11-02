package vn.yourhrm.people.internal.application.contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.JobCatalogue;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.application.contract.ContractRecords.Employment;
import vn.yourhrm.people.internal.domain.contract.ContractType;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Probation rules (BR-PPL-051, US-PPL-048; Labour Code Art. 24-27 [verify-legal]): maximum length by probation level
 * from the legal parameter {@code contract.probation.max_days}, once per job (per person and legal entity), no
 * probation in a contract shorter than {@code contract.short_term.threshold_months}, and probation pay at least
 * {@code contract.probation.min_pay_pct} of the job's salary, also cross-checked with the salary record valid on the
 * start date ({@code hr.salary_record.probation_pct}, read directly: the salary stream owns the table).
 *
 * <p>Core has no "job level" for probation: the level is given with the terms, or derived from the employee's grade
 * code through the tenant setting {@code hr.probation_level_by_grade}.
 */
@Component
public class ProbationRules {

    private final HrDb db;
    private final PeopleLegalParameters legal;
    private final Settings settings;
    private final JobCatalogue jobs;

    public ProbationRules(HrDb db, PeopleLegalParameters legal, Settings settings, JobCatalogue jobs) {
        this.db = db;
        this.legal = legal;
        this.settings = settings;
        this.jobs = jobs;
    }

    /** {@code end} is inclusive; true when the term is shorter than the legal short-term threshold. */
    public boolean shortTerm(Employment emp, LocalDate start, LocalDate end) {
        int months = legal.require(ContractKeys.SHORT_TERM_THRESHOLD_MONTHS, scope(emp), start).asInt();
        return end != null && end.plusDays(1).isBefore(start.plusMonths(months));
    }

    /**
     * Validates the probation of new terms and returns the probation days to store, or null when the contract has no
     * probation.
     */
    @HrTransactional(readOnly = true)
    public Integer check(Employment emp, ContractType type, ContractTerms t) {
        Integer days;
        if (type == ContractType.PROBATION) {
            days = (int) ChronoUnit.DAYS.between(t.startDate(), t.endDate()) + 1;
            if (t.probationDays() != null && t.probationDays() != days.intValue()) {
                throw ApiException.unprocessable("invalid-probation-days",
                        "A probation agreement's probation days are its term (" + days + " days)");
            }
        } else if (t.probationDays() != null && t.probationDays() > 0) {
            if (!type.probationClauseAllowed()) {
                throw ApiException.unprocessable("probation-not-allowed",
                        "A " + type + " contract cannot include probation");
            }
            if (shortTerm(emp, t.startDate(), t.endDate())) {
                throw ApiException.unprocessable("probation-not-allowed",
                        "No probation for a contract shorter than the legal threshold (BR-PPL-051)");
            }
            days = t.probationDays();
            if (t.endDate() != null && t.startDate().plusDays(days - 1L).isAfter(t.endDate())) {
                throw ApiException.unprocessable("invalid-probation-days", "The probation exceeds the contract term");
            }
        } else {
            return null;
        }
        Assignment asg = assignment(emp.id(), t.startDate());
        String level = level(emp, t, asg);
        Map<String, Object> limits = legal.require(ContractKeys.PROBATION_MAX_DAYS, scope(emp), t.startDate()).asMap();
        Object max = limits.get(level);
        if (max == null) {
            throw ApiException.unprocessable("invalid-probation-level", "Unknown probation level " + level,
                    Map.of("allowed", List.copyOf(limits.keySet())));
        }
        if (days > ((Number) max).intValue()) {
            throw ApiException.unprocessable("probation-too-long", "Probation of " + days + " days exceeds the legal "
                    + "maximum of " + max + " days for level " + level, Map.of("maxDays", max, "level", level));
        }
        UUID job = t.jobTitleId() != null ? t.jobTitleId() : asg.jobTitleId();
        if (probationAlreadyDone(emp, job)) {
            throw ApiException.unprocessable("probation-already-done",
                    "The employee already had a probation for this job with this employer (once per job, BR-PPL-051)");
        }
        BigDecimal floor = legal.require(ContractKeys.PROBATION_MIN_PAY_PCT, scope(emp), t.startDate()).asDecimal();
        if (t.probationPayPct() != null && t.probationPayPct().compareTo(floor) < 0) {
            throw belowFloor(t.probationPayPct(), floor);
        }
        BigDecimal recorded = db.jdbc().sql("""
                        select probation_pct from hr.salary_record
                         where tenant_id = :t and employment_id = :e and superseded_at is null
                           and valid_during @> cast(:d as date)""")
                .param("t", tenant()).param("e", emp.id()).param("d", t.startDate())
                .query(BigDecimal.class).optional().orElse(null);
        if (recorded != null && recorded.compareTo(floor) < 0) {
            throw belowFloor(recorded, floor);
        }
        return days;
    }

    private static ApiException belowFloor(BigDecimal pct, BigDecimal floor) {
        return ApiException.unprocessable("probation-pay-below-minimum", "Probation pay of " + pct.stripTrailingZeros()
                .toPlainString() + "% is below the legal floor of " + floor.stripTrailingZeros().toPlainString() + "%",
                Map.of("minPct", floor));
    }

    private String level(Employment emp, ContractTerms t, Assignment asg) {
        if (t.probationLevelCode() != null && !t.probationLevelCode().isBlank()) {
            return t.probationLevelCode();
        }
        if (asg.gradeId() != null) {
            Optional<String> code = Optional.ofNullable(jobs.grades(List.of(asg.gradeId()), t.startDate())
                    .get(asg.gradeId())).map(JobCatalogue.Grade::code);
            Object byGrade = settings.value(ContractKeys.SETTING_PROBATION_LEVEL_BY_GRADE,
                    new Settings.Scope(emp.legalEntityId(), null), t.startDate());
            if (code.isPresent() && byGrade instanceof Map<?, ?> m && m.get(code.get()) != null) {
                return String.valueOf(m.get(code.get()));
            }
        }
        throw ApiException.unprocessable("probation-level-required", "The probation level is required: give it with the "
                + "terms or map the employee's grade in the setting " + ContractKeys.SETTING_PROBATION_LEVEL_BY_GRADE);
    }

    /**
     * A probation (agreement or clause) already in force for the same job title (null = no job title) in any
     * employment of the person with the same legal entity.
     */
    private boolean probationAlreadyDone(Employment emp, UUID jobTitleId) {
        return db.jdbc().sql("""
                        select exists (
                          select 1 from hr.contract c
                            join hr.employment e on e.tenant_id = c.tenant_id and e.id = c.employment_id
                            left join lateral (
                                  select a.job_title_id from hr.assignment a
                                   where a.tenant_id = c.tenant_id and a.employment_id = c.employment_id
                                     and a.assignment_type = 'PRIMARY' and a.superseded_at is null
                                   order by (a.valid_during @> c.start_date) desc, a.valid_from desc limit 1) j on true
                           where c.tenant_id = :t and e.person_id = :p and e.legal_entity_id = :le
                             and c.status in ('ACTIVE','SUSPENDED','EXPIRED','TERMINATED')
                             and (c.contract_type_code = 'PROBATION' or coalesce(c.probation_days, 0) > 0)
                             and j.job_title_id is not distinct from cast(:job as uuid))""")
                .param("t", tenant()).param("p", emp.personId()).param("le", emp.legalEntityId())
                .param("job", jobTitleId)
                .query(Boolean.class).single();
    }

    record Assignment(UUID jobTitleId, UUID gradeId) {
    }

    private Assignment assignment(UUID employmentId, LocalDate on) {
        return db.jdbc().sql("""
                        select job_title_id, grade_id from hr.assignment
                         where tenant_id = :t and employment_id = :e and assignment_type = 'PRIMARY'
                           and superseded_at is null
                         order by (valid_during @> cast(:d as date)) desc, valid_from desc limit 1""")
                .param("t", tenant()).param("e", employmentId).param("d", on)
                .query((rs, n) -> new Assignment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional().orElse(new Assignment(null, null));
    }

    static Scope scope(Employment emp) {
        return new Scope(emp.legalEntityId(), null);
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
