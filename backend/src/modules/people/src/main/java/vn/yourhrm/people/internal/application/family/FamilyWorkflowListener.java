package vn.yourhrm.people.internal.application.family;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/**
 * Applies the outcome of {@code hr.DEPENDANT_CHANGE} approvals (US-PPL-029) in a People transaction, then acknowledges
 * the callback; a failure is reported to Core, which keeps the callback for retry in the workflow console.
 */
@Component
class FamilyWorkflowListener {

    private static final Logger log = LoggerFactory.getLogger(FamilyWorkflowListener.class);

    private final DependantService dependants;
    private final WorkflowService workflow;

    FamilyWorkflowListener(DependantService dependants, WorkflowService workflow) {
        this.dependants = dependants;
        this.workflow = workflow;
    }

    @EventListener
    void on(WorkflowCompleted e) {
        if (!FamilyCatalogues.PROCESS_DEPENDANT_CHANGE.equals(e.processTypeCode())) {
            return;
        }
        Map<String, Object> result;
        try {
            result = dependants.applyOutcome(e.subjectId(), e.outcome());
        } catch (RuntimeException ex) {
            log.warn("Applying {} outcome {} to dependant {} failed: {}", e.processTypeCode(), e.outcome(),
                    e.subjectId(), ex.getMessage());
            workflow.reportCallbackFailure(e.callbackId(), ex.getMessage());
            return;
        }
        workflow.acknowledgeCallback(e.callbackId(), result);
    }
}
