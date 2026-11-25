package vn.yourhrm.attendance.internal.application.approval;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService;
import vn.yourhrm.attendance.internal.application.period.PeriodService;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.HistoryEntry;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/**
 * Applies the final outcome of {@code att.PERIOD_REOPEN} and {@code att.BALANCE_ADJUST} approvals (ATT-P-11) in a
 * T&amp;A transaction and acknowledges Core's callback; a failure is reported to Core, which keeps it for retry in the
 * workflow console.
 */
@Component
class ChangeRequestWorkflowListener {

    private static final Logger log = LoggerFactory.getLogger(ChangeRequestWorkflowListener.class);

    private final PeriodService periods;
    private final LeaveBalanceService balances;
    private final WorkflowService workflow;

    ChangeRequestWorkflowListener(PeriodService periods, LeaveBalanceService balances, WorkflowService workflow) {
        this.periods = periods;
        this.balances = balances;
        this.workflow = workflow;
    }

    @EventListener
    void on(WorkflowCompleted e) {
        boolean reopen = ApprovalCatalogues.PROCESS_PERIOD_REOPEN.equals(e.processTypeCode());
        boolean adjust = ApprovalCatalogues.PROCESS_BALANCE_ADJUST.equals(e.processTypeCode());
        if (!reopen && !adjust) {
            return;
        }
        Map<String, Object> result;
        try {
            UUID decider = decider(e.history());
            result = reopen ? periods.applyReopenOutcome(e.subjectId(), e.outcome(), decider)
                    : balances.applyAdjustOutcome(e.subjectId(), e.outcome(), decider);
        } catch (RuntimeException ex) {
            log.warn("Applying {} outcome {} to change request {} failed: {}", e.processTypeCode(), e.outcome(),
                    e.subjectId(), ex.getMessage());
            workflow.reportCallbackFailure(e.callbackId(), ex.getMessage());
            return;
        }
        workflow.acknowledgeCallback(e.callbackId(), result);
    }

    /** The user of the last decision in the trail (approve / reject / withdraw). */
    static UUID decider(List<HistoryEntry> history) {
        UUID who = null;
        for (HistoryEntry h : history) {
            if (h.actorUserId() != null && !"SUBMIT".equals(h.action()) && !"COMMENT".equals(h.action())) {
                who = h.actorUserId();
            }
        }
        return who;
    }
}
