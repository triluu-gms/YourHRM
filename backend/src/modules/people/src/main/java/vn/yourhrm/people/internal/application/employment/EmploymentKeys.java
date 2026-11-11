package vn.yourhrm.people.internal.application.employment;

import java.util.List;

/** Keys of the employment stream: legal parameters, plan limits, number series, process types, codes. */
public final class EmploymentKeys {

    private EmploymentKeys() {
    }

    /**
     * Legal parameter (MAP) insurance type → rule, e.g. {@code {"SI":"FIRST_CONTRACT","HI":"FIRST_CONTRACT",
     * "UI":"FIRST_CONTRACT","OA_OD":"EACH_EMPLOYER"}} (R3-03, [verify-legal]; the PM believes HI may be
     * HIGHEST_SALARY: switching is a parameter change only).
     */
    public static final String INSURANCE_MULTI_CONTRACT_RULE = "insurance.multi_contract_rule";

    /** Plan limit of employments ACTIVE at the same time in the tenant (IN-23); absent = no limit. */
    public static final String LIMIT_ACTIVE_EMPLOYEES = "limit.active_employees";

    /** Core number series of employee numbers (BR-CORE-085), unique in the tenant (DDL {@code employment_no_uq}). */
    public static final String EMPLOYEE_NO_SEQUENCE = "EMPLOYEE_NO";

    /** Core process types. */
    public static final String HIRE_PROCESS = "hr.HIRE";
    public static final String PRIMARY_CHANGE_PROCESS = "hr.PRIMARY_EMPLOYMENT_CHANGE";

    /** Workflow subject types. */
    static final String HIRE_SUBJECT = "hr.employment";
    static final String PRIMARY_SUBJECT = "hr.primary_employment";

    public static final String PURPOSE_PIT = "PIT_FAMILY_DEDUCTION";

    public static final List<String> INSURANCE_TYPES = List.of("SI", "HI", "UI", "OA_OD");
    public static final List<String> RULES = List.of("FIRST_CONTRACT", "HIGHEST_SALARY", "EACH_EMPLOYER");

    /** Employment statuses counting as "live" for the per-LE rule and the primary designation. */
    public static final List<String> LIVE_STATUSES = List.of("PRE_HIRE", "ACTIVE", "SUSPENDED");

    /** Idempotency markers (consumer names in {@code hr.inbox_message}). */
    static final String HIRE_APPROVAL_MARKER = "hr.employment.hire_approval";
    static final String ACTIVATION_MARKER = "hr.employment.activation";
    static final String PRIMARY_APPROVAL_MARKER = "hr.primary_employment.approval";
    static final String PRIMARY_ENDED_MARKER = "hr.primary_employment.ended";
    static final String INSURANCE_ENDED_MARKER = "hr.insurance.employment_ended";
    static final String HEADCOUNT_MARKER = "hr.headcount.snapshot";
}
