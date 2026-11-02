package vn.yourhrm.people.internal.domain.contract;

import java.util.Arrays;
import java.util.Optional;

/**
 * Contract types of BR-PPL-050 / US-PPL-047 ({@code hr.contract.contract_type_code}; the DDL comment lists PROBATION,
 * DEFINITE, INDEFINITE, SERVICE, APPRENTICE and has no CHECK, SEASONAL and INTERNSHIP are added here). The legal
 * numbers (maximum term, the short-term threshold, probation limits) are legal parameters, not properties of the type.
 *
 * <ul>
 *   <li>{@code countsAsDefinite}: counted by the two-consecutive-definite rule (BR-PPL-053) and by the auto-conversion
 *       monitor (BR-PPL-054). A contract under one month (SEASONAL) is a definite-term contract under Labour Code 2019
 *       Art. 20, so it counts [verify-legal].</li>
 *   <li>{@code labourContract}: a labour contract (SERVICE / collaborator agreements are not, Q7 / GAP-059): only
 *       labour contracts can carry a probation clause, an appendix or a suspension.</li>
 * </ul>
 */
public enum ContractType {

    /** Probation agreement (thỏa thuận thử việc, Art. 24); its term is the probation period. */
    PROBATION(false, true, false),
    /** Definite term, at most {@code contract.definite.max_months} months. */
    DEFINITE(true, true, true),
    INDEFINITE(false, false, true),
    /** Seasonal / short work shorter than {@code contract.short_term.threshold_months} month(s). */
    SEASONAL(true, true, true),
    /** Apprenticeship (học nghề, Art. 61). */
    APPRENTICE(false, false, false),
    /** Internship (thực tập). */
    INTERNSHIP(false, false, false),
    /** Service / collaborator agreement: not a labour contract. */
    SERVICE(false, false, false);

    private final boolean countsAsDefinite;
    private final boolean requiresEndDate;
    private final boolean labourContract;

    ContractType(boolean countsAsDefinite, boolean requiresEndDate, boolean labourContract) {
        this.countsAsDefinite = countsAsDefinite;
        this.requiresEndDate = requiresEndDate;
        this.labourContract = labourContract;
    }

    public boolean countsAsDefinite() {
        return countsAsDefinite;
    }

    public boolean requiresEndDate() {
        return requiresEndDate;
    }

    public boolean labourContract() {
        return labourContract;
    }

    /** A probation clause (probation_days) may be agreed in the contract itself (Art. 24.1). */
    public boolean probationClauseAllowed() {
        return this == DEFINITE || this == INDEFINITE;
    }

    public static Optional<ContractType> of(String code) {
        return Arrays.stream(values()).filter(t -> t.name().equals(code)).findFirst();
    }
}
