package vn.yourhrm.people.api;

/**
 * Permission codes owned by People (registered through People's {@code PermissionCatalogue}; owner module {@code hr},
 * so every code starts with {@code hr.}). Data scope (legal entity, org unit, location, SELF, reports) comes from the
 * role grant and is applied per employment with {@code AuthorizationTarget.Employee}.
 */
public final class PeoplePermissions {

    private PeoplePermissions() {
    }

    /** Employee list, profile, employment, assignment and contract summaries (US-PPL-015/016). P3 values masked. */
    public static final String EMPLOYEE_VIEW = "hr.employee.view";
    /** Employee list export through the SaaS list export, masked unless the exporter may unmask (BR-PPL-152). */
    public static final String EMPLOYEE_EXPORT = "hr.employee.export";

    /** Person master data: identity, documents, addresses, contacts, qualification, SI / tax profile (EP-PPL-01). */
    public static final String PERSON_CREATE = "hr.person.create";
    public static final String PERSON_UPDATE = "hr.person.update";
    /** Unmask P3 values (national id, tax code, SI no., bank account, ethnicity, religion, health); every use audited. */
    public static final String PERSON_VIEW_SENSITIVE = "hr.person.view_sensitive";
    /** Merge two duplicate persons (US-PPL-004); privileged. */
    public static final String PERSON_MERGE = "hr.person.merge";

    /** Hire (direct and concurrent), cancel a pre-hire, employment data, primary employment, insurance participation. */
    public static final String EMPLOYMENT_CREATE = "hr.employment.create";
    public static final String EMPLOYMENT_UPDATE = "hr.employment.update";

    /** Job changes, temporary transfers, corrections of assignment history (EP-PPL-04). */
    public static final String ASSIGNMENT_UPDATE = "hr.assignment.update";

    /** BR-PPL-044 {@code compensation}: view / propose agreed pay. Every view and change is audited. */
    public static final String COMPENSATION_VIEW = "hr.compensation.view";
    public static final String COMPENSATION_UPDATE = "hr.compensation.update";

    /** Labour contracts, appendices, suspensions, probation results (EP-PPL-06). */
    public static final String CONTRACT_VIEW = "hr.contract.view";
    public static final String CONTRACT_CREATE = "hr.contract.create";
    public static final String CONTRACT_UPDATE = "hr.contract.update";

    /** HR decisions (BR-PPL-130). */
    public static final String DECISION_VIEW = "hr.decision.view";
    public static final String DECISION_CREATE = "hr.decision.create";
    public static final String DECISION_UPDATE = "hr.decision.update";

    /** Family members and PIT dependants (EP-PPL-03). */
    public static final String DEPENDANT_VIEW = "hr.dependant.view";
    public static final String DEPENDANT_UPDATE = "hr.dependant.update";

    /** Salary bank accounts (BR-PPL-015); changes need approval, ESS changes need step-up. */
    public static final String BANK_ACCOUNT_VIEW = "hr.bank_account.view";
    public static final String BANK_ACCOUNT_UPDATE = "hr.bank_account.update";

    /** Tax residency and VN presence periods (BR-PPL-023), incl. review of Payroll's 183-day proposals. */
    public static final String TAX_RESIDENCY_UPDATE = "hr.tax_residency.update";

    /** Special statuses (BR-PPL-021); health-related types are sensitive. */
    public static final String SPECIAL_STATUS_VIEW = "hr.special_status.view";
    public static final String SPECIAL_STATUS_UPDATE = "hr.special_status.update";

    /** Work permits of foreign workers (BR-PPL-020). */
    public static final String WORK_PERMIT_UPDATE = "hr.work_permit.update";

    /** Separation: request, approve via workflow, protected-employee override (EP-PPL-08). */
    public static final String SEPARATION_VIEW = "hr.separation.view";
    public static final String SEPARATION_CREATE = "hr.separation.create";
    public static final String SEPARATION_UPDATE = "hr.separation.update";

    /** Labour book, 6-monthly report, monthly fluctuation notice and their submission log (EP-PPL-09). */
    public static final String STATUTORY_REPORT_VIEW = "hr.statutory_report.view";
    public static final String STATUTORY_REPORT_EXECUTE = "hr.statutory_report.execute";

    /** Opening-balance employee import types (EP-PPL-13). */
    public static final String IMPORT_RUN = "hr.import.run";

    /** DSR export / anonymisation handlers of People data (BR-PPL-153); privileged. */
    public static final String DSR_EXECUTE = "hr.dsr.execute";

    /**
     * Employee self-service (EP-PPL-10), granted with scope SELF: my profile, my employment and documents, and my
     * requests (dependant, bank account with step-up, resignation).
     */
    public static final String ESS_VIEW = "hr.ess.view";
    public static final String ESS_REQUEST = "hr.ess.request";
}
