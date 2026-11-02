package vn.yourhrm.people.internal.application.person;

/**
 * Keys the person stream reads: legal parameters (Legal Parameter Service, all values {@code [verify-legal]}), tenant
 * settings (Core, registered by {@link PersonCatalogues}) and Core code lists used for validation.
 */
public final class PersonKeys {

    private PersonKeys() {
    }

    // ---- legal parameters ------------------------------------------------------------------------------------------
    /** Age (years) under which a worker is a minor (BR-PPL-021, Labour Code Art. 143: 18). */
    public static final String LP_MINOR_AGE_YEARS = "person.minor.age_years";
    /**
     * Age (months) from which a worker is an elderly worker, MAP by gender code (e.g. {@code {"M": 744, "F": 720}}),
     * the retirement-age roadmap value in effect (C16, Decree 135/2020).
     */
    public static final String LP_ELDERLY_AGE_MONTHS = "person.elderly_worker.age_months";
    /**
     * Date (ISO) from which the 12-digit personal identification number is the personal tax code (Circular 86/2024,
     * BR-PPL-013 / C18). Absent = no defaulting.
     */
    public static final String LP_PERSONAL_ID_AS_TAX_CODE_FROM = "person.tax_code.personal_id_as_tax_code_from";

    // ---- tenant settings -------------------------------------------------------------------------------------------
    /** Days before an identity document's expiry date when HR is alerted (US-PPL-007). */
    public static final String SETTING_ID_DOC_ALERT_DAYS = "hr.identity_doc_expiry_alert_days";
    /** First / second work-permit expiry alert offsets (BR-PPL-020: 60 / 45 days, configurable). */
    public static final String SETTING_WP_ALERT_DAYS_FIRST = "hr.work_permit_expiry_alert_days_first";
    public static final String SETTING_WP_ALERT_DAYS_SECOND = "hr.work_permit_expiry_alert_days_second";

    // ---- Core code lists -------------------------------------------------------------------------------------------
    public static final String CL_GENDER = "GENDER";
    public static final String CL_ETHNICITY = "ETHNICITY";
    public static final String CL_RELIGION = "RELIGION";
    /** Initial health-care (KCB) facilities (US-PPL-009). */
    public static final String CL_HI_FACILITY = "HI_FACILITY";
    public static final String CL_QUALIFICATION_LEVEL = "QUALIFICATION_LEVEL";
    public static final String CL_VOCATIONAL_SKILL_LEVEL = "VOCATIONAL_SKILL_LEVEL";
    public static final String CL_GENERAL_EDUCATION = "GENERAL_EDUCATION";
    public static final String CL_VISA_TYPE = "VISA_TYPE";

    // ---- workflow / documents / notifications ----------------------------------------------------------------------
    public static final String PROCESS_TAX_RESIDENCY = "hr.TAX_RESIDENCY";
    public static final String EVENT_ID_DOC_EXPIRING = "hr.IdentityDocumentExpiring";
    public static final String EVENT_WORK_PERMIT_EXPIRING = "hr.WorkPermitExpiring";

    public static final String DOC_IDENTITY_SCAN = "HR_IDENTITY_DOC_SCAN";
    public static final String DOC_WORK_PERMIT_SCAN = "HR_WORK_PERMIT_SCAN";
    public static final String DOC_SPECIAL_STATUS_EVIDENCE = "HR_SPECIAL_STATUS_EVIDENCE";
    public static final String DOC_TAX_RESIDENCY_EVIDENCE = "HR_TAX_RESIDENCY_EVIDENCE";
    public static final String DOC_QUALIFICATION_SCAN = "HR_QUALIFICATION_SCAN";

    public static final String OWNER_IDENTITY_DOC = "hr.person_identity_doc";
    public static final String OWNER_WORK_PERMIT = "hr.work_permit";
    public static final String OWNER_SPECIAL_STATUS = "hr.special_status";
    public static final String OWNER_TAX_RESIDENCY = "hr.tax_residency";
    public static final String OWNER_VN_PRESENCE = "hr.vn_presence_period";
    public static final String OWNER_QUALIFICATION = "hr.education_qualification";

    /** Marker on special-status rows derived from the birth date (minor / elderly worker). */
    public static final String DERIVED_FROM_BIRTH_DATE = "DERIVED_BIRTH_DATE";
    /** {@code visa_type_code} marker of a legacy 10-digit MST kept as history in hr.person_identity_doc (OTHER). */
    public static final String LEGACY_TAX_CODE_MARKER = "LEGACY_TAX_CODE";
}
