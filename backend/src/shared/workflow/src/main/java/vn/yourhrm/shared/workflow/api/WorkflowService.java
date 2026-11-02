package vn.yourhrm.shared.workflow.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Starting approvals and acknowledging their outcome (ADR-0007, US-CORE-052). Implemented by Core.
 *
 * <p>The owner module calls {@link #start} inside the transaction that changes its own record, so the request and
 * the workflow commit together. It applies the outcome when {@link WorkflowEvents.WorkflowCompleted} arrives, in its
 * own transaction, and then calls {@link #acknowledgeCallback} (or {@link #reportCallbackFailure}). Core never
 * touches the owner's tables.
 */
public interface WorkflowService {

    /**
     * Starts (or, for a known idempotency key, returns) the approval of a subject. The requester is the current
     * user. The approver chain is resolved and snapshotted now (BR-CORE-058, organisation as of today).
     */
    StartResult start(StartRequest request);

    /** The owner module applied the outcome. Idempotent. */
    void acknowledgeCallback(UUID callbackId, Map<String, Object> moduleResult);

    /** The owner module could not apply the outcome; Core keeps it for retry in the workflow console. */
    void reportCallbackFailure(UUID callbackId, String error);

    /**
     * @param processTypeCode   registered process type
     * @param subjectType       the owner's record type, e.g. {@code att.leave_request}
     * @param subjectId         the owner's record id (soft reference)
     * @param subjectVersion    the owner's optimistic version at submit (stale-callback guard), optional
     * @param idempotencyKey    unique per process type; a retried submit returns the same instance
     * @param onBehalfOfUserId  the employee's user when HR submits for them, optional
     * @param subjectPersonId   the person the request is about (DSR, "my requests"), optional
     * @param legalEntityId     organisation of the request: drives conditions, resolvers and data scope
     * @param orgUnitId         the subject's org unit (line-manager resolution starts here)
     * @param locationId        optional
     * @param employeeCategoryId optional, for conditions
     * @param requestSummary    shown to approvers; must follow the process type's summary schema
     * @param conditionValues   values of the process type's condition fields (and REQUEST_FIELD approvers)
     * @param pickedApprovers   step key -> user id for REQUESTER_PICK steps
     */
    record StartRequest(String processTypeCode, String subjectType, UUID subjectId, Integer subjectVersion,
            String idempotencyKey, UUID onBehalfOfUserId, UUID subjectPersonId, UUID legalEntityId, UUID orgUnitId,
            UUID locationId, UUID employeeCategoryId, Map<String, Object> requestSummary,
            Map<String, Object> conditionValues, Map<String, UUID> pickedApprovers) {

        public StartRequest {
            Objects.requireNonNull(processTypeCode, "processTypeCode");
            Objects.requireNonNull(subjectType, "subjectType");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            requestSummary = requestSummary == null ? Map.of() : Map.copyOf(requestSummary);
            conditionValues = conditionValues == null ? Map.of() : Map.copyOf(conditionValues);
            pickedApprovers = pickedApprovers == null ? Map.of() : Map.copyOf(pickedApprovers);
        }
    }

    /**
     * @param created false when an instance with the same idempotency key already existed
     * @param status  instance status (APPROVED right away when every step was skipped)
     */
    record StartResult(UUID instanceId, String status, boolean created) {
    }
}
