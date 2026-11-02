package vn.yourhrm.people.internal.application.assignment;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.internal.domain.assignment.ChangeReason;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/**
 * Applies the outcome of job-change approvals (US-PPL-036): on {@code core.WorkflowCompleted} of
 * {@code hr.JOB_CHANGE} / {@code hr.TEMPORARY_TRANSFER} the proposal is applied (APPROVED) or closed, in a NEW People
 * transaction after the workflow's commit, then the callback is acknowledged (or reported as failed for the workflow
 * console) in a new Core transaction. Idempotent per proposal.
 */
@Component
class AssignmentApprovals {

    private static final Logger log = LoggerFactory.getLogger(AssignmentApprovals.class);

    private final AssignmentService assignments;
    private final WorkflowService workflow;
    private final TransactionTemplate hrTx;
    private final TransactionTemplate coreTx;

    AssignmentApprovals(AssignmentService assignments, WorkflowService workflow,
            @Qualifier(HrDb.TX) PlatformTransactionManager hrTm, PlatformTransactionManager coreTm) {
        this.assignments = assignments;
        this.workflow = workflow;
        this.hrTx = new TransactionTemplate(hrTm);
        this.hrTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.coreTx = new TransactionTemplate(coreTm);
        this.coreTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(WorkflowCompleted e) {
        if (!ChangeReason.PROCESS_TYPES.contains(e.processTypeCode())) {
            return;
        }
        try {
            var applied = hrTx.execute(s -> assignments.resolveProposal(e.subjectId(), e.instanceId(), e.outcome()));
            Map<String, Object> result = applied != null && applied.isPresent()
                    ? Map.of("assignmentId", applied.get().row().id().toString(), "status",
                            applied.get().inForce() ? "APPLIED" : "SCHEDULED")
                    : Map.of("status", "APPROVED".equals(e.outcome()) ? "ALREADY_APPLIED" : "CLOSED");
            coreTx.executeWithoutResult(s -> workflow.acknowledgeCallback(e.callbackId(), result));
        } catch (RuntimeException ex) {
            log.warn("Job change {} could not be applied after workflow {}", e.subjectId(), e.instanceId(), ex);
            coreTx.executeWithoutResult(s -> workflow.reportCallbackFailure(e.callbackId(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }
}
