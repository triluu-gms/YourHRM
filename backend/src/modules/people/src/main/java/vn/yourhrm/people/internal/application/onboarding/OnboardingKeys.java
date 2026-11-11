package vn.yourhrm.people.internal.application.onboarding;

/**
 * Codes of the opening-balance employee import (EP-PPL-13, NFR-PPL-007, ADR-0037). Core's engine takes ONE data sheet
 * per file and per import type, so the employee master is split into one import type per sheet, chained by
 * {@code dependsOn}: Core refuses an opening-balance upload until the types it depends on are committed, and each
 * sheet is validated against the data the earlier sheets committed (cross-sheet checks).
 */
public final class OnboardingKeys {

    private OnboardingKeys() {
    }

    /** Person + identifiers + employment (one row per employment); the root that Payroll / T&A loads depend on. */
    public static final String MASTER = "OB_EMPLOYEE_MASTER";
    public static final String CONTACT = "OB_EMP_CONTACT";
    public static final String ASSIGNMENT = "OB_EMP_ASSIGNMENT";
    public static final String CONTRACT = "OB_EMP_CONTRACT";
    public static final String SALARY = "OB_EMP_SALARY";
    public static final String SALARY_HISTORY = "OB_EMP_SALARY_HISTORY";
    public static final String FAMILY = "OB_EMP_FAMILY";
    public static final String BANK = "OB_EMP_BANK";
    public static final String SERVICE_HISTORY = "OB_EMP_SERVICE_HISTORY";

    /** Template version stamped in generated files. */
    public static final String TEMPLATE_VERSION = "1";

    public static final String SOURCE = "OPENING_LOAD";

    // reconciliation checks (control sheet vs staged vs committed, ADR-0037 §3 / DESIGN §13.5)
    public static final String CHECK_HEADCOUNT = "HEADCOUNT";
    public static final String CHECK_SALARY_TOTAL = "SALARY_TOTAL";
    public static final String CHECK_DEPENDANTS = "DEPENDANTS";
    public static final String CHECK_BANK_ACCOUNTS = "BANK_ACCOUNTS";

    /** Legal separation ground stored on the COMPLETED separation that carries imported history (US-PPL-092). */
    public static final String HISTORY_GROUND = "OPENING_BALANCE_HISTORY";
}
