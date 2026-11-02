package vn.yourhrm.people.internal.application.salary;

/**
 * Legal-parameter keys of the salary stream (BR-PPL-041, US-PPL-043; all values [verify-legal], e.g. Decree 74/2024
 * regional minimum wages and the 85 % probation floor of Labour Code Art. 26).
 */
public final class SalaryLegalKeys {

    private SalaryLegalKeys() {
    }

    /** MAP minimum-wage region (I, II, III, IV) → monthly minimum wage in VND. */
    public static final String MIN_WAGE_MONTHLY = "salary.min_wage.monthly_by_region";
    /** MAP minimum-wage region → hourly minimum wage in VND (HOURLY salary kind). */
    public static final String MIN_WAGE_HOURLY = "salary.min_wage.hourly_by_region";
    /** Probation pay is at least this % of the job's salary (e.g. 85). */
    public static final String PROBATION_MIN_PCT = "salary.probation.min_pct";
}
