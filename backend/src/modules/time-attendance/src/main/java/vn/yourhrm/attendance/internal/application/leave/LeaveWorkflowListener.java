package vn.yourhrm.attendance.internal.application.leave;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.HistoryEntry;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/** Applies the outcome of a leave approval ({@code att.LEAVE}) and acknowledges Core's callback. */
@Component
class LeaveWorkflowListener {

    private static final Logger log = LoggerFactory.getLogger(LeaveWorkflowListener.class);

    private final LeaveRequestService requests;
    private final WorkflowService workflow;

    LeaveWorkflowListener(LeaveRequestService requests, WorkflowService workflow) {
        this.requests = requests;
        this.workflow = workflow;
    }

    @EventListener
    void onWorkflow(WorkflowCompleted e) {
        if (!LeaveCatalogues.PROCESS_LEAVE.equals(e.processTypeCode())) {
            return;
        }
        UUID decider = e.history().stream().filter(h -> h.stepNo() != null).reduce((a, b) -> b)
                .map(HistoryEntry::actorUserId).orElse(null);
        try {
            Map<String, Object> result = requests.applyOutcome(e.subjectId(), e.outcome(), decider);
            workflow.acknowledgeCallback(e.callbackId(), result);
        } catch (RuntimeException ex) {
            log.warn("Applying {} outcome {} to leave request {} failed: {}", e.processTypeCode(), e.outcome(),
                    e.subjectId(), ex.getMessage());
            workflow.reportCallbackFailure(e.callbackId(), ex.getMessage());
        }
    }
}
