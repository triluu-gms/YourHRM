package vn.yourhrm.attendance.internal.application;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.common.legal.LegalParameters;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.legal.LegalParameters.Value;

/**
 * T&amp;A's access to the Legal Parameter Service (ADR-0010 / ADR-0025): statutory values are read here, never
 * hard-coded. The key names are T&amp;A proposals: the sources define no T&amp;A parameter keys yet, and every value is
 * {@code [verify-legal]} (BRD §5). Configuration checks that depend on a missing parameter are reported as warnings,
 * never silently passed.
 */
@Component
public class AttendanceLegalParameters {

    /** VN-03: continuous working minutes after which a break is due (BRD default 6h = 360). */
    public static final String BREAK_DUE_AFTER_MIN = "LABOUR.BREAK.DUE_AFTER_MIN";
    /** VN-03: minimum break minutes for day work (BRD default 30). */
    public static final String BREAK_MIN_DAY = "LABOUR.BREAK.MIN_DAY_MIN";
    /** VN-03: minimum break minutes for night work (BRD default 45). */
    public static final String BREAK_MIN_NIGHT = "LABOUR.BREAK.MIN_NIGHT_MIN";
    /** VN-01: maximum normal working hours per week (BRD default 48). */
    public static final String MAX_NORMAL_HOURS_WEEK = "LABOUR.NORMAL_HOURS.MAX_WEEK";
    /** VN-01: maximum normal working hours per day (BRD default 8; 10 when counted per week). */
    public static final String MAX_NORMAL_HOURS_DAY = "LABOUR.NORMAL_HOURS.MAX_DAY";
    /** VN-02: start of the night window, text "HH:mm" (BRD default 22:00). Shared by NW (night_hours) and OT-*-N. */
    public static final String NIGHT_START = "LABOUR.NIGHT.START";
    /** VN-02: end of the night window, text "HH:mm" (BRD default 06:00, next day when before the start). */
    public static final String NIGHT_END = "LABOUR.NIGHT.END";
    /** VN-16 / BR-ATT-035: paid reduction minutes per day while raising a child under 12 months (BRD default 60). */
    public static final String PROTECTED_BREAK_CHILD_MIN = "LABOUR.PROTECTED_BREAK.CHILD_UNDER_12M_MIN";
    /** VN-16 / BR-ATT-035: paid reduction minutes per day for a pregnant worker from month 7 (BRD default 60). */
    public static final String PROTECTED_BREAK_PREGNANCY_MIN = "LABOUR.PROTECTED_BREAK.PREGNANCY_MIN";
    /**
     * VN-16 / BR-ATT-035 (BLLĐ 2019 Art. 137(4), ND145 Art. 80): paid break minutes per day during menstruation,
     * counted as working time (BRD default 30).
     */
    public static final String PROTECTED_BREAK_MENSTRUAL_MIN = "LABOUR.PROTECTED_BREAK.MENSTRUAL_MIN";
    /**
     * VN-16 / BR-ATT-035: days per calendar month the menstrual break applies to (BRD default 3, a statutory minimum:
     * a LEGAL_ENTITY override may grant more where the pack allows it).
     */
    public static final String PROTECTED_BREAK_MENSTRUAL_DAYS = "LABOUR.PROTECTED_BREAK.MENSTRUAL_DAYS_PER_MONTH";
    /** VN-07 / BR-ATT-045: normal + overtime hours on a working day (BRD default 12). */
    public static final String OT_MAX_DAY_TOTAL_HOURS = "LABOUR.OT.MAX_DAY_TOTAL_HOURS";
    /** VN-07 / BR-ATT-045: overtime hours on a weekly rest day or holiday (BRD default 12). */
    public static final String OT_MAX_DAY_OFF_HOURS = "LABOUR.OT.MAX_DAY_OFF_HOURS";
    /** VN-07 / BR-ATT-046: overtime hours per calendar month (BRD default 40). */
    public static final String OT_MAX_MONTH_HOURS = "LABOUR.OT.MAX_MONTH_HOURS";
    /** VN-07 / BR-ATT-046: overtime hours per calendar year, standard regime (BRD default 200). */
    public static final String OT_MAX_YEAR_HOURS = "LABOUR.OT.MAX_YEAR_HOURS";
    /** VN-07/08 / BR-ATT-046/049: overtime hours per year in the notified extended regime (BRD default 300). */
    public static final String OT_MAX_YEAR_EXTENDED_HOURS = "LABOUR.OT.MAX_YEAR_EXTENDED_HOURS";
    /**
     * VN-16 / BR-ATT-048: overtime rule per protected-worker type, a JSON object such as
     * {@code {"PREGNANCY":"BLOCK","CHILD_UNDER_12M":"CONSENT","MINOR":"BLOCK","DISABILITY":"CONSENT",
     * "ELDERLY_WORKER":"ALLOW","HAZARDOUS_JOB":"ALLOW"}}; a type it does not name is BLOCK.
     */
    public static final String OT_PROTECTED_RULES = "LABOUR.OT.PROTECTED_RULES";
    /** VN-12 / BR-ATT-057: annual leave days per year, normal conditions (BRD default 12 working days). */
    public static final String LEAVE_ANNUAL_BASE_DAYS = "LEAVE.ANNUAL.BASE_DAYS";
    /**
     * VN-12 / BR-ATT-057: annual leave days for minors, workers with a disability and heavy / hazardous jobs (BRD default
     * 14). The 16-day tier (especially heavy / hazardous jobs) needs a job-list flag People does not publish yet.
     */
    public static final String LEAVE_ANNUAL_PROTECTED_DAYS = "LEAVE.ANNUAL.PROTECTED_DAYS";
    /** VN-12 / BR-ATT-057: full years of service per seniority step (BRD default 5). */
    public static final String LEAVE_SENIORITY_STEP_YEARS = "LEAVE.ANNUAL.SENIORITY_STEP_YEARS";
    /** VN-12 / BR-ATT-057: extra days per seniority step (BRD default 1). */
    public static final String LEAVE_SENIORITY_STEP_DAYS = "LEAVE.ANNUAL.SENIORITY_STEP_DAYS";
    /**
     * BR-ATT-059 (ND145 Art. 65): calendar days of leave per leave year that still count as working time, by leave
     * category, a JSON object such as {@code {"SICK":60,"UNPAID_AGREED":30,"PERSONAL_UNPAID":30}}; a category it does not
     * name counts fully when its type counts as service.
     */
    public static final String LEAVE_SERVICE_COUNTED_CAPS = "LEAVE.SERVICE.COUNTED_CAPS_DAYS";
    /** VN-15 / BR-ATT-065: months of maternity leave before an early return is allowed (BRD default 4). */
    public static final String LEAVE_MATERNITY_MIN_MONTHS = "LEAVE.MATERNITY.MIN_MONTHS_BEFORE_EARLY_RETURN";
    /**
     * VN-13 / BR-ATT-056: days of personal leave per event, a JSON object such as
     * {@code {"MARRIAGE_SELF":3,"MARRIAGE_CHILD":1,"BEREAVEMENT":3,"UNPAID_FAMILY":1}}; read when the statutory types
     * are seeded (their maximum per request).
     */
    public static final String LEAVE_PERSONAL_DAYS = "LEAVE.PERSONAL.DAYS";

    private final ObjectProvider<LegalParameters> legalParameters;

    public AttendanceLegalParameters(ObjectProvider<LegalParameters> legalParameters) {
        this.legalParameters = legalParameters;
    }

    public Optional<Value> find(String key, Scope scope, LocalDate asOf) {
        LegalParameters lps = legalParameters.getIfAvailable();
        return lps == null ? Optional.empty() : lps.value(key, scope, asOf);
    }
}
