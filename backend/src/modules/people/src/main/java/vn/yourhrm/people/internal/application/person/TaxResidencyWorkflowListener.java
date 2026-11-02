package vn.yourhrm.people.internal.application.person;

import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/**
 * Applies the outcome of {@code hr.TAX_RESIDENCY} approvals (US-PPL-013) in a People transaction and acknowledges the
 * callback; a failure is reported to Core's workflow console for retry.
 */
@Component
class TaxResidencyWorkflowListener {

    private final TaxResidencyService residency;
    private final WorkflowService workflow;

    TaxResidencyWorkflowListener(TaxResidencyService residency, WorkflowService workflow) {
        this.residency = residency;
        this.workflow = workflow;
    }

    @EventListener
    void on(WorkflowCompleted e) {
        if (!PersonKeys.PROCESS_TAX_RESIDENCY.equals(e.processTypeCode())) {
            return;
        }
        Runnable apply = () -> {
            try {
                residency.applyOutcome(e);
                workflow.acknowledgeCallback(e.callbackId(), Map.of("taxResidencyId", e.subjectId().toString()));
            } catch (RuntimeException ex) {
                workflow.reportCallbackFailure(e.callbackId(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        };
        if (TenantContext.isBound()) {
            apply.run();
        } else {
            TenantContext.runWith(new TenantContext(e.tenantId(), null, null, null, null), apply);
        }
    }
}
