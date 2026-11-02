package vn.yourhrm.people.internal.application.contract;

/**
 * Legal-parameter keys (Legal Parameter Service, all {@code [verify-legal]}) and tenant-setting keys of the
 * labour-contract stream. Statutory numbers are never hard-coded (ADR-0010); a missing legal parameter refuses the
 * operation (422 {@code legal-parameter-missing}).
 */
public final class ContractKeys {

    private ContractKeys() {
    }

    // ------------------------------------------------------------------ legal parameters (BRD examples in brackets)

    /** Maximum term of a definite contract in months [36] (Art. 20.1.b, BR-PPL-053). */
    public static final String DEFINITE_MAX_MONTHS = "contract.definite.max_months";
    /** Number of consecutive definite contracts after which the next must be indefinite [2] (Art. 20.2). */
    public static final String DEFINITE_MAX_CONSECUTIVE = "contract.definite.max_consecutive";
    /**
     * Legal exceptions to the consecutive-definite rule, a list of codes [ELDERLY_WORKER, FOREIGN_WORKER,
     * SOE_MANAGER, UNION_OFFICER_TERM] (Art. 20.2, 149, 151, 177).
     */
    public static final String DEFINITE_EXCEPTIONS = "contract.definite.consecutive_exceptions";
    /** Days worked after a definite contract ended without a new one, after which it converts [30] (Art. 20.2). */
    public static final String DEFINITE_AUTO_CONVERT_DAYS = "contract.definite.auto_convert_after_days";
    /** Below this many months a contract is "short term": SEASONAL type, and no probation [1] (Art. 24.3). */
    public static final String SHORT_TERM_THRESHOLD_MONTHS = "contract.short_term.threshold_months";
    /**
     * Maximum probation days by probation level, a map level → days [ENTERPRISE_MANAGER 180, COLLEGE_OR_ABOVE 60,
     * INTERMEDIATE 30, OTHER 6] (Art. 25). Calendar days; the BRD's "6 working days" is approximated as days.
     */
    public static final String PROBATION_MAX_DAYS = "contract.probation.max_days";
    /** Probation pay floor in % of the job's salary [85] (Art. 26). */
    public static final String PROBATION_MIN_PAY_PCT = "contract.probation.min_pay_pct";
    /** Days within which a suspended employee must return after the suspension ends [15] (Art. 31). */
    public static final String SUSPENSION_RETURN_DAYS = "contract.suspension.return_window_days";

    // ------------------------------------------------------------------ tenant settings (ContractSettings)

    /** Expiry alert offsets in days before the end date, e.g. [45, 30, 15] (BR-PPL-054). */
    public static final String SETTING_EXPIRY_ALERT_DAYS = "hr.contract_expiry_alert_days";
    /** Days before the probation end when HR and managers are reminded to record the result (BR-PPL-052). */
    public static final String SETTING_PROBATION_ALERT_DAYS = "hr.probation_end_alert_days";
    /** Further exception codes of the consecutive-definite rule the tenant allows ("other configured cases"). */
    public static final String SETTING_DEFINITE_EXTRA_EXCEPTIONS = "hr.contract_definite_extra_exceptions";
    /** Probation level per Core grade code, e.g. {"M1": "ENTERPRISE_MANAGER"}, used when the request names none. */
    public static final String SETTING_PROBATION_LEVEL_BY_GRADE = "hr.probation_level_by_grade";

    // ------------------------------------------------------------------ Core

    /** Core number series of contracts (BR-CORE-085), per legal entity. */
    public static final String CONTRACT_SEQUENCE = "CONTRACT_NO";
}
