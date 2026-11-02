package vn.yourhrm.people.internal.application.bank;

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
 * Applies the outcome of {@code hr.BANK_ACCOUNT_CHANGE} approvals (US-PPL-032) in a People transaction and acknowledges
 * the callback; a failure is reported to Core for retry.
 */
@Component
class BankWorkflowListener {

    private static final Logger log = LoggerFactory.getLogger(BankWorkflowListener.class);

    private final BankAccountService accounts;
    private final WorkflowService workflow;

    BankWorkflowListener(BankAccountService accounts, WorkflowService workflow) {
        this.accounts = accounts;
        this.workflow = workflow;
    }

    @EventListener
    void on(WorkflowCompleted e) {
        if (!BankCatalogues.PROCESS_BANK_ACCOUNT_CHANGE.equals(e.processTypeCode())) {
            return;
        }
        UUID requester = e.history().stream().filter(h -> "SUBMIT".equals(h.action())).findFirst()
                .map(HistoryEntry::actorUserId).orElse(null);
        Map<String, Object> result;
        try {
            result = accounts.applyOutcome(e.subjectId(), e.outcome(), requester);
        } catch (RuntimeException ex) {
            log.warn("Applying {} outcome {} to bank account {} failed: {}", e.processTypeCode(), e.outcome(),
                    e.subjectId(), ex.getMessage());
            workflow.reportCallbackFailure(e.callbackId(), ex.getMessage());
            return;
        }
        workflow.acknowledgeCallback(e.callbackId(), result);
    }
}
