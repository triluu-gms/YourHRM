package vn.yourhrm.shared.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lifecycle events of the approval engine (BR-CORE-060, US-CORE-058), {@code core.Workflow*}. In-process Spring
 * events for now; transport through Core's outbox (at-least-once, ordered per instance) is TECH:outbox. Consumers
 * must be idempotent. {@code SlaBreached} comes with SLAs (P2).
 */
public final class WorkflowEvents {

    private WorkflowEvents() {
    }

    /** {@code core.WorkflowSubmitted}. */
    public record WorkflowSubmitted(UUID tenantId, UUID instanceId, String processTypeCode, String subjectType,
            UUID subjectId, UUID requesterUserId) {
    }

    /** {@code core.StepAssigned}: a task was created for (or reassigned to) a user. */
    public record StepAssigned(UUID tenantId, UUID instanceId, UUID taskId, int stepNo, String stepKey,
            UUID assigneeUserId, String assignedVia) {
    }

    /** {@code core.WorkflowStepCompleted}: a step finished with {@code outcome} (APPROVED, REJECTED, RETURNED ...). */
    public record StepCompleted(UUID tenantId, UUID instanceId, int stepNo, String stepKey, String outcome,
            UUID actedByUserId) {
    }

    /** {@code core.WorkflowWithdrawn}: the requester withdrew before the final decision. */
    public record WorkflowWithdrawn(UUID tenantId, UUID instanceId, String processTypeCode, UUID subjectId) {
    }

    /**
     * {@code core.WorkflowCompleted}: the final outcome (APPROVED, REJECTED, WITHDRAWN, CANCELLED) with the approval
     * history (US-CORE-060). The owner applies it and acknowledges {@code callbackId}.
     */
    public record WorkflowCompleted(UUID tenantId, UUID instanceId, UUID callbackId, String processTypeCode,
            String ownerModule, String subjectType, UUID subjectId, Integer subjectVersion, String outcome,
            List<HistoryEntry> history) {

        public WorkflowCompleted {
            history = List.copyOf(history);
        }
    }

    /** One line of the approval trail. */
    public record HistoryEntry(Instant occurredAt, String action, UUID actorUserId, UUID onBehalfOfUserId,
            Integer stepNo, String comment) {
    }
}
