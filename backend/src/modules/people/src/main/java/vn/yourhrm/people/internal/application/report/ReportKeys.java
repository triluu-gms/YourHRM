package vn.yourhrm.people.internal.application.report;

/**
 * Legal-parameter keys (all {@code [verify-legal]}), tenant settings, document types / templates and notification
 * events of the statutory-report stream (EP-PPL-09, BR-PPL-140..142). Form codes, layouts and deadlines are legal
 * parameters, never constants (ADR-0010).
 */
public final class ReportKeys {

    private ReportKeys() {
    }

    // ------------------------------------------------------------------ legal parameters (BRD examples in brackets)

    /**
     * Periods of the 6-monthly labour change report (Decree 145/2020 Art. 4): MAP period code → {@code {"start_month",
     * "start_year_offset", "end_month", "end_year_offset", "due_month", "due_day", "due_year_offset"}}
     * [JUNE: Jan-Jun, due 5 June; DECEMBER: Jul-Dec, due 5 December]. The offsets are relative to the report year.
     */
    public static final String CHANGE_6M_PERIODS = "report.labour_change_6m.periods";
    /** Prescribed layout of the 6-monthly report: {@code {"form_code": "01/PLI", "title_vi", "title_en"}}. */
    public static final String CHANGE_6M_FORM = "report.labour_change_6m.form";
    /**
     * Due date of the monthly labour fluctuation notice (Employment Law 2025, form unconfirmed): {@code {"month_offset":
     * 1, "day": 3}} = the 3rd of the following month [verify-legal, high uncertainty].
     */
    public static final String FLUCTUATION_DUE = "report.labour_fluctuation.due";
    /**
     * Parameterised layout of the monthly fluctuation notice: {@code {"form_code", "title_vi", "title_en", "columns":
     * [column keys]}}; the column keys are those of {@link LabourReportService#FLUCTUATION_COLUMNS}.
     */
    public static final String FLUCTUATION_LAYOUT = "report.labour_fluctuation.layout";

    // ------------------------------------------------------------------ tenant settings

    /** Days before a statutory report's due date when HR is alerted, e.g. [15, 5, 1]. */
    public static final String SETTING_ALERT_DAYS = "hr.statutory_report.alert_days";

    // ------------------------------------------------------------------ Core

    public static final String OWNER_REPORT = "hr.statutory_report";
    /** Owner of a generated labour-book entry: the employment ({@code ownerEntityId} = employment id). */
    public static final String OWNER_LABOUR_BOOK_ENTRY = "hr.labour_book_entry";
    public static final String REPORT_DOC = "HR_STATUTORY_REPORT";
    public static final String RECEIPT_DOC = "HR_STATUTORY_RECEIPT";
    public static final String LABOUR_BOOK_ENTRY_DOC = "HR_LABOUR_BOOK_ENTRY";
    /** Core document templates (purpose REPORT): one labour-book entry, the 6-monthly report. */
    public static final String TEMPLATE_LABOUR_BOOK_ENTRY = "HR_LABOUR_BOOK_ENTRY";
    public static final String TEMPLATE_LABOUR_CHANGE_6M = "HR_LABOUR_CHANGE_6M";

    public static final String EVENT_REPORT_DUE = "hr.StatutoryReportDue";

    public static final String LABOUR_BOOK_EXPORT = "LABOUR_BOOK_EXPORT";
    public static final String LABOUR_CHANGE_6M = "LABOUR_CHANGE_6M";
    public static final String LABOUR_FLUCTUATION_MONTHLY = "LABOUR_FLUCTUATION_MONTHLY";
}
