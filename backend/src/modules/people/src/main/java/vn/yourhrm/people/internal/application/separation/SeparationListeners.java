package vn.yourhrm.people.internal.application.separation;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.api.ContractEvents.ProbationCompleted;
import vn.yourhrm.people.internal.application.contract.ProbationService;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/**
 * In-process hooks of the separation stream:
 * <ul>
 *   <li>{@code core.WorkflowCompleted} of {@code hr.SEPARATION} / {@code hr.RESIGNATION} /
 *       {@code hr.RESIGNATION_WITHDRAWAL}: the outcome is applied in a NEW People transaction after the workflow's
 *       commit, then the callback is acknowledged (or reported as failed for the workflow console); idempotent;</li>
 *   <li>{@code people.ProbationCompleted} with FAIL (US-PPL-049/061): a draft separation with the ground
 *       PROBATION_FAILED is opened after the probation result committed; a failure is logged and never undoes the
 *       probation result.</li>
 * </ul>
 */
@Component
class SeparationListeners {

    private static final Logger log = LoggerFactory.getLogger(SeparationListeners.class);

    private final SeparationService separations;
    private final WorkflowService workflow;
    private final TransactionTemplate hrTx;
    private final TransactionTemplate coreTx;

    SeparationListeners(SeparationService separations, WorkflowService workflow,
            @Qualifier(HrDb.TX) PlatformTransactionManager hrTm, PlatformTransactionManager coreTm) {
        this.separations = separations;
        this.workflow = workflow;
        this.hrTx = new TransactionTemplate(hrTm);
        this.hrTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.coreTx = new TransactionTemplate(coreTm);
        this.coreTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(WorkflowCompleted e) {
        boolean withdrawal = SeparationKeys.PROCESS_RESIGNATION_WITHDRAWAL.equals(e.processTypeCode());
        if (!withdrawal && !SeparationKeys.PROCESS_SEPARATION.equals(e.processTypeCode())
                && !SeparationKeys.PROCESS_RESIGNATION.equals(e.processTypeCode())) {
            return;
        }
        try {
            coreTx.executeWithoutResult(c -> {
                Optional<String> applied = hrTx.execute(s -> withdrawal
                        ? separations.applyWithdrawalOutcome(e.subjectId(), e.outcome())
                        : separations.applyOutcome(e.subjectId(), e.instanceId(), e.outcome()));
                workflow.acknowledgeCallback(e.callbackId(), Map.of("status",
                        applied != null && applied.isPresent() ? applied.get() : "ALREADY_HANDLED"));
            });
        } catch (RuntimeException ex) {
            log.warn("Separation {} could not apply {} of workflow {}", e.subjectId(), e.outcome(), e.instanceId(), ex);
            coreTx.executeWithoutResult(s -> workflow.reportCallbackFailure(e.callbackId(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(ProbationCompleted e) {
        if (!ProbationService.FAIL.equals(e.result())) {
            return;
        }
        try {
            coreTx.executeWithoutResult(c -> hrTx.execute(s -> separations.openForProbationFailure(e)));
        } catch (RuntimeException ex) {
            log.warn("No draft separation opened for failed probation of employment {}: {}", e.employmentId(),
                    ex.getMessage());
        }
    }
}
