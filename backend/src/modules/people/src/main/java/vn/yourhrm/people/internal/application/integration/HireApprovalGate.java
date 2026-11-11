package vn.yourhrm.people.internal.application.integration;

import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Integration fix F1 (NFR-PPL-002, US-PPL-019/080): the first {@code AssignmentChanged} / {@code SalaryChanged} of a
 * hire must not be announced while the hire still waits for approval (a back-dated hire would otherwise reach
 * Payroll / T&amp;A / Core before anyone approved it). A hire is <em>pending</em> while its employment is PRE_HIRE and
 * the latest non-cancelled HIRE decision is DRAFT / PENDING_APPROVAL (the decision is the persisted approval state of
 * the hire, see {@code HireDecisions}). Employments without a HIRE decision (opening-balance import) are never held.
 *
 * <p>Used by the assignment / salary creators and daily jobs (they hold the events) and released by
 * {@code HireService} on approval / activation through {@code AssignmentService.releaseHeldInitialEvents} and
 * {@code SalaryService.releaseHeldInitialEvents}; the daily-job markers ({@code hr.assignment.due},
 * {@code hr.salary.due}) guarantee each event is published exactly once.
 */
@Component
public class HireApprovalGate {

    private final HrDb db;

    public HireApprovalGate(HrDb db) {
        this.db = db;
    }

    /** Must the events of {@code employmentId} wait for the hire approval? Runs in the caller's People transaction. */
    public boolean pending(UUID employmentId) {
        return db.jdbc().sql("""
                        select exists (
                          select 1 from hr.employment e
                           where e.tenant_id = :t and e.id = :e and e.status = 'PRE_HIRE'
                             and (select d.status from hr.decision d
                                   where d.tenant_id = e.tenant_id and d.employment_id = e.id
                                     and d.decision_type_code = 'HIRE'
                                   order by (d.status = 'CANCELLED'), d.created_at desc limit 1)
                                 in ('DRAFT', 'PENDING_APPROVAL'))""")
                .param("t", TenantContext.current().tenantId()).param("e", employmentId)
                .query(Boolean.class).single();
    }
}
