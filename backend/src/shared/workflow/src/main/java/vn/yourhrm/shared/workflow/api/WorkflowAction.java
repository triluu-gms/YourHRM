package vn.yourhrm.shared.workflow.api;

/**
 * User actions of the approval engine (BR-CORE-054; DDL {@code workflow_action_log_action_ck}). A process type
 * lists the ones it allows; {@link #BULK_APPROVE} is a flag that lets its tasks be approved in bulk.
 */
public enum WorkflowAction {
    APPROVE, REJECT, RETURN, REQUEST_INFO, PROVIDE_INFO, WITHDRAW, FORWARD, COMMENT, RESUBMIT, BULK_APPROVE
}
