package vn.yourhrm.people.internal.application.employment;

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
 * Applies the outcome of {@code hr.HIRE} (US-PPL-019) and {@code hr.PRIMARY_EMPLOYMENT_CHANGE} (US-PPL-022) in a new
 * People transaction after the workflow committed, then acknowledges the callback (or reports the failure).
 */
@Component
class EmploymentApprovals {

    private static final Logger log = LoggerFactory.getLogger(EmploymentApprovals.class);

    private final HireService hires;
    private final PrimaryEmploymentService primaries;
    private final WorkflowService workflow;
    private final TransactionTemplate hrTx;
    private final TransactionTemplate coreTx;

    EmploymentApprovals(HireService hires, PrimaryEmploymentService primaries, WorkflowService workflow,
            @Qualifier(HrDb.TX) PlatformTransactionManager hrTm, PlatformTransactionManager coreTm) {
        this.hires = hires;
        this.primaries = primaries;
        this.workflow = workflow;
        this.hrTx = new TransactionTemplate(hrTm);
        this.hrTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.coreTx = new TransactionTemplate(coreTm);
        this.coreTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(WorkflowCompleted e) {
        boolean hire = EmploymentKeys.HIRE_PROCESS.equals(e.processTypeCode());
        boolean primary = EmploymentKeys.PRIMARY_CHANGE_PROCESS.equals(e.processTypeCode());
        if (!hire && !primary) {
            return;
        }
        try {
            coreTx.executeWithoutResult(c -> {
                Map<String, Object> result;
                if (hire) {
                    var row = hrTx.execute(s -> hires.resolve(e.subjectId(), e.instanceId(), e.outcome()));
                    result = row != null && row.isPresent() ? Map.of("employmentStatus", row.get().status())
                            : Map.of("status", "ALREADY_APPLIED");
                } else {
                    var id = hrTx.execute(s -> primaries.resolve(e.subjectId(), e.outcome()));
                    result = id != null && id.isPresent() ? Map.of("primaryEmploymentId", id.get().toString())
                            : Map.of("status", "APPROVED".equals(e.outcome()) ? "ALREADY_APPLIED" : "CLOSED");
                }
                workflow.acknowledgeCallback(e.callbackId(), result);
            });
        } catch (RuntimeException ex) {
            log.warn("{} {} could not be applied after workflow {}", e.processTypeCode(), e.subjectId(),
                    e.instanceId(), ex);
            coreTx.executeWithoutResult(s -> workflow.reportCallbackFailure(e.callbackId(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }
}
