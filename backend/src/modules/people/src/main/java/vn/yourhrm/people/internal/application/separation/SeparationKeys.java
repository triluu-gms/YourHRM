package vn.yourhrm.people.internal.application.separation;

/**
 * Legal-parameter keys (Legal Parameter Service, all {@code [verify-legal]}), tenant settings, process types and
 * notification events of the separation stream (EP-PPL-08). Statutory numbers and codes are never hard-coded
 * (ADR-0010); a missing legal parameter refuses the operation (422 {@code legal-parameter-missing}).
 */
public final class SeparationKeys {

    private SeparationKeys() {
    }

    // ------------------------------------------------------------------ legal parameters (BRD examples in brackets)

    /**
     * Statutory attributes per legal ground (MAP ground code → object), US-PPL-060 / BR-PPL-070/071/074:
     * {@code statutory_code} (the SI/UI termination code used for the D02 / UI declarations and reports),
     * {@code notice_side} (EMPLOYEE = Art. 35 rules, EMPLOYER = Art. 36 rules, NONE = no notice) and
     * {@code severance} (NONE | SEVERANCE_ALLOWANCE Art. 46 | JOB_LOSS_ALLOWANCE Art. 47).
     */
    public static final String LEGAL_GROUNDS = "separation.legal_grounds";
    /**
     * Required notice per side and contract category (MAP side → category → {@code {"days": n, "basis":
     * "CALENDAR"|"WORKING"}}); categories INDEFINITE [45], DEFINITE_LONG [30], DEFINITE_SHORT [3 working],
     * PROBATION [0] (Art. 27, 35.1, 36.2).
     */
    public static final String NOTICE_DAYS = "separation.notice.days";
    /** A definite contract of at least this many months is DEFINITE_LONG [12] (Art. 35.1.b). */
    public static final String NOTICE_LONG_MIN_MONTHS = "separation.notice.definite_long_min_months";
    /**
     * Special trades with longer notice (Decree 145/2020 Art. 7): MAP trade code → category →
     * {@code {"days": n}} [120] or {@code {"term_fraction": x}} [0.25 of the term for contracts under 12 months].
     */
    public static final String NOTICE_SPECIAL_TRADES = "separation.notice.special_trades";
    /** Cases without notice per side (MAP side → list of codes; Art. 35.2 employee, Art. 36.3 employer). */
    public static final String NOTICE_EXEMPT_REASONS = "separation.notice.exempt_reasons";
    /**
     * Consequences of a notice shortfall per side (MAP side → list of consequence codes, Art. 40 / 41), e.g. EMPLOYEE:
     * [NO_SEVERANCE, PAY_HALF_MONTH_SALARY, PAY_WAGES_FOR_SHORTFALL_DAYS]; EMPLOYER: [PAY_WAGES_FOR_SHORTFALL_DAYS].
     * The code {@link #NO_SEVERANCE} makes the severance eligibility NOT_ELIGIBLE.
     */
    public static final String NOTICE_SHORTFALL_CONSEQUENCES = "separation.notice.shortfall_consequences";

    /** Consequence code that forfeits the severance allowance (Art. 40.1 unlawful unilateral termination). */
    public static final String NO_SEVERANCE = "NO_SEVERANCE";

    // ------------------------------------------------------------------ tenant settings (SeparationCatalogues)

    /**
     * Tenant sub-reasons under a legal ground (JSON list of {@code {"code", "groundCode", "nameVi", "nameEn",
     * "active"}}); a sub-reason never creates a ground (US-PPL-060).
     */
    public static final String SETTING_SUB_REASONS = "hr.separation.sub_reasons";
    /** Days before Payroll's settlement due date when HR is alerted, e.g. [3, 1] (BR-PPL-075). */
    public static final String SETTING_SETTLEMENT_ALERT_DAYS = "hr.separation.settlement_alert_days";

    // ------------------------------------------------------------------ Core

    /** HR-initiated separations (and drafts from the probation / contract-expiry hooks). */
    public static final String PROCESS_SEPARATION = "hr.SEPARATION";
    /** ESS resignations: line manager, then HR (US-PPL-065). */
    public static final String PROCESS_RESIGNATION = "hr.RESIGNATION";
    /** Withdrawal of an approved resignation: needs HR's consent (US-PPL-065). */
    public static final String PROCESS_RESIGNATION_WITHDRAWAL = "hr.RESIGNATION_WITHDRAWAL";

    public static final String SUBJECT_TYPE = "hr.separation";
    public static final String EVIDENCE_DOC = "HR_SEPARATION_EVIDENCE";

    public static final String EVENT_SETTLEMENT_DUE = "hr.FinalSettlementDue";
    public static final String EVENT_DRAFT_OPENED = "hr.SeparationDraftOpened";
}
