package vn.yourhrm.people.internal.application.salary;

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
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/**
 * Applies the outcome of {@code hr.SALARY_CHANGE} approvals (US-PPL-042) in a new People transaction after the
 * workflow committed, then acknowledges the callback (or reports the failure) in a new Core transaction.
 */
@Component
class SalaryApprovals {

    private static final Logger log = LoggerFactory.getLogger(SalaryApprovals.class);

    private final SalaryService salaries;
    private final WorkflowService workflow;
    private final TransactionTemplate hrTx;
    private final TransactionTemplate coreTx;

    SalaryApprovals(SalaryService salaries, WorkflowService workflow,
            @Qualifier(HrDb.TX) PlatformTransactionManager hrTm, PlatformTransactionManager coreTm) {
        this.salaries = salaries;
        this.workflow = workflow;
        this.hrTx = new TransactionTemplate(hrTm);
        this.hrTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.coreTx = new TransactionTemplate(coreTm);
        this.coreTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(WorkflowCompleted e) {
        if (!SalaryCatalogues.SALARY_CHANGE.equals(e.processTypeCode())) {
            return;
        }
        try {
            // A fresh Core transaction around the People one: the Core calls of the change (numbering, audit) and
            // the acknowledgement must not join the workflow's already committed transaction (AFTER_COMMIT phase).
            coreTx.executeWithoutResult(c -> {
                var applied = hrTx.execute(s -> salaries.resolveProposal(e.subjectId(), e.instanceId(), e.outcome()));
                Map<String, Object> result = applied != null && applied.isPresent()
                        ? Map.of("salaryRecordId", applied.get().row().id().toString(), "status",
                                applied.get().inForce() ? "APPLIED" : "SCHEDULED")
                        : Map.of("status", "APPROVED".equals(e.outcome()) ? "ALREADY_APPLIED" : "CLOSED");
                workflow.acknowledgeCallback(e.callbackId(), result);
            });
        } catch (RuntimeException ex) {
            log.warn("Salary change {} could not be applied after workflow {}", e.subjectId(), e.instanceId(), ex);
            coreTx.executeWithoutResult(s -> workflow.reportCallbackFailure(e.callbackId(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }
}
