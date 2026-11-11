package vn.yourhrm.people.internal.application.employment;

import java.util.Optional;
import java.util.UUID;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * The HIRE decision of an employment doubles as the persisted approval state of the hire (no DDL column for it):
 * PENDING_APPROVAL while {@code hr.HIRE} runs, APPROVED / SIGNED once approved, CANCELLED when rejected or the
 * pre-hire is cancelled. The hire flow issues it with
 * {@link vn.yourhrm.people.internal.application.DecisionService#issuePending} and approves it with
 * {@code DecisionService.approve} (integration fix F2).
 */
final class HireDecisions {

    private HireDecisions() {
    }

    record HireDecision(UUID id, String decisionNo, String status, int version) {
    }

    static Optional<HireDecision> of(HrDb db, UUID employmentId) {
        return db.jdbc().sql("""
                        select id, decision_no, status, version from hr.decision
                         where tenant_id = :t and employment_id = :e and decision_type_code = 'HIRE'
                         order by (status = 'CANCELLED'), created_at desc limit 1""")
                .param("t", TenantContext.current().tenantId()).param("e", employmentId)
                .query((rs, n) -> new HireDecision(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getInt(4)))
                .optional();
    }

    /** Is the hire approved (its HIRE decision APPROVED or SIGNED)? */
    static boolean approved(HrDb db, UUID employmentId) {
        return of(db, employmentId).map(d -> "APPROVED".equals(d.status()) || "SIGNED".equals(d.status()))
                .orElse(false);
    }

    /** "PENDING", "APPROVED", "REJECTED_OR_CANCELLED" or "NONE" (employment loaded / imported without a decision). */
    static String approvalState(HrDb db, UUID employmentId) {
        return of(db, employmentId).map(d -> switch (d.status()) {
            case "PENDING_APPROVAL", "DRAFT" -> "PENDING";
            case "APPROVED", "SIGNED" -> "APPROVED";
            default -> "REJECTED_OR_CANCELLED";
        }).orElse("NONE");
    }

    /**
     * Records the {@code hr.HIRE} workflow instance on the pending decision (no status change). Integration fix F2:
     * status transitions go through {@code DecisionService.issuePending / approve / cancel}; DecisionService has no
     * "link workflow" operation, so only this column update stays here.
     */
    static void linkWorkflow(HrDb db, UUID decisionId, UUID workflowInstanceId) {
        db.jdbc().sql("""
                        update hr.decision set workflow_instance_id = :wf, version = version + 1, updated_at = now(),
                               updated_by = :a
                         where tenant_id = :t and id = :id and status = 'PENDING_APPROVAL'""")
                .param("wf", workflowInstanceId)
                .param("a", TenantContext.current().userAccountId())
                .param("t", TenantContext.current().tenantId()).param("id", decisionId).update();
    }
}
