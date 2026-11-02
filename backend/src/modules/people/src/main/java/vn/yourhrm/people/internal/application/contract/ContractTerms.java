package vn.yourhrm.people.internal.application.contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The agreed terms of a new contract (US-PPL-047/048/050).
 *
 * @param contractTypeCode      a {@link vn.yourhrm.people.internal.domain.contract.ContractType} code
 * @param endDate               INCLUSIVE; required for PROBATION / DEFINITE / SEASONAL, absent for INDEFINITE
 * @param probationDays         probation clause of a DEFINITE / INDEFINITE contract (a PROBATION agreement derives it
 *                              from its term)
 * @param probationLevelCode    key of the legal parameter {@code contract.probation.max_days}; when null it is derived
 *                              from the employee's grade through the tenant setting {@code hr.probation_level_by_grade}
 * @param probationPayPct       probation pay in % of the job's salary, checked against the legal floor (the salary
 *                              record's {@code probation_pct} is checked too)
 * @param jobTitleId            the job probation is for ("once per job"); null = the primary assignment's job title
 * @param definiteExceptionCode legal exception allowing a further definite contract (BR-PPL-053)
 * @param definiteExceptionNote free-text justification of the exception (kept in the audit log)
 */
public record ContractTerms(String contractTypeCode, LocalDate startDate, LocalDate endDate, Integer probationDays,
        String signatoryName, String signatoryTitle, String probationLevelCode, BigDecimal probationPayPct,
        UUID jobTitleId, String definiteExceptionCode, String definiteExceptionNote) {

    /** These terms with another type, start and end (renewal / conversion / contract after probation). */
    public ContractTerms withTerm(String type, LocalDate start, LocalDate end) {
        return new ContractTerms(type, start, end, probationDays, signatoryName, signatoryTitle, probationLevelCode,
                probationPayPct, jobTitleId, definiteExceptionCode, definiteExceptionNote);
    }
}
