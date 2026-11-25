package vn.yourhrm.attendance.internal.application.overtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.HistoryEntry;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/**
 * Applies the outcome of {@code att.OT_REQUEST} approvals in a T&amp;A transaction and acknowledges the callback; a
 * failure is reported to Core, which keeps it for retry in the workflow console.
 */
@Component
class OvertimeWorkflowListener {

    private static final Logger log = LoggerFactory.getLogger(OvertimeWorkflowListener.class);

    private final OvertimeService overtime;
    private final WorkflowService workflow;

    OvertimeWorkflowListener(OvertimeService overtime, WorkflowService workflow) {
        this.overtime = overtime;
        this.workflow = workflow;
    }

    @EventListener
    void on(WorkflowCompleted e) {
        if (!OvertimeCatalogues.PROCESS_OT_REQUEST.equals(e.processTypeCode())) {
            return;
        }
        Map<String, Object> result;
        try {
            result = overtime.applyOutcome(e.subjectId(), e.outcome(), decider(e.history()));
        } catch (RuntimeException ex) {
            log.warn("Applying {} outcome {} to overtime request {} failed: {}", e.processTypeCode(), e.outcome(),
                    e.subjectId(), ex.getMessage());
            workflow.reportCallbackFailure(e.callbackId(), ex.getMessage());
            return;
        }
        workflow.acknowledgeCallback(e.callbackId(), result);
    }

    /** The user of the last decision in the trail (approve / reject / withdraw). */
    private static UUID decider(List<HistoryEntry> history) {
        UUID who = null;
        for (HistoryEntry h : history) {
            if (h.actorUserId() != null && !"SUBMIT".equals(h.action()) && !"COMMENT".equals(h.action())) {
                who = h.actorUserId();
            }
        }
        return who;
    }
}
