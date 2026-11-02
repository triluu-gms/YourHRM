package vn.yourhrm.people.internal.application.assignment;

/**
 * Legal-parameter keys of the assignment stream (Labour Code 2019 Art. 29, BR-PPL-035; all values [verify-legal]).
 * Read through {@code PeopleLegalParameters}; a missing key refuses the operation.
 */
public final class AssignmentLegalKeys {

    private AssignmentLegalKeys() {
    }

    /** Cumulative working days of temporary transfer per calendar year allowed without written consent (e.g. 60). */
    public static final String TEMP_TRANSFER_MAX_DAYS = "assignment.temp_transfer.max_working_days_per_year";
    /** Minimum notice before a temporary transfer starts, in working days (e.g. 3). */
    public static final String TEMP_TRANSFER_NOTICE_DAYS = "assignment.temp_transfer.notice_working_days";
    /** The new job's wage is at least this % of the previous wage (e.g. 85). */
    public static final String TEMP_TRANSFER_WAGE_FLOOR_PCT = "assignment.temp_transfer.wage_floor_pct";
    /** The previous wage is kept for this many working days (e.g. 30). */
    public static final String TEMP_TRANSFER_PREVIOUS_WAGE_DAYS = "assignment.temp_transfer.previous_wage_working_days";
}
