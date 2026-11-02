package vn.yourhrm.core.internal.application;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Notifications.SendRequest;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.StepAssigned;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.StepCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;

/**
 * Notifies approvers and requesters of workflow activity (US-CORE-063): a task assigned (or reassigned), a request
 * returned, a final decision. Runs in the engine's transaction, so the inbox item commits with the change. A
 * notification problem is logged and never blocks the approval. Dedupe keys make redelivered events harmless.
 */
@Component
public class WorkflowNotifier {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNotifier.class);

    private final NotificationService notifications;
    private final JdbcClient jdbc;

    public WorkflowNotifier(NotificationService notifications, JdbcClient jdbc) {
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    @EventListener
    void on(StepAssigned e) {
        record Info(String requester, String processVi, String processEn, String stepVi, String stepEn) {
        }
        Info info = jdbc.sql("""
                        select coalesce(u.display_name, u.login), pt.name_vi, pt.name_en,
                               i.approver_chain_snapshot -> (:n - 1) ->> 'nameVi',
                               coalesce(i.approver_chain_snapshot -> (:n - 1) ->> 'nameEn',
                                        i.approver_chain_snapshot -> (:n - 1) ->> 'nameVi')
                          from core.workflow_instance i
                          join core.process_type pt on pt.process_type_code = i.process_type_code
                          join core.user_account u on u.tenant_id = i.tenant_id and u.id = i.requester_user_id
                         where i.tenant_id = :t and i.id = :i""")
                .param("t", e.tenantId()).param("i", e.instanceId()).param("n", e.stepNo())
                .query((rs, n) -> new Info(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .single();
        boolean en = english(e.tenantId(), e.assigneeUserId());
        send(CoreNotificationTemplates.TASK_ASSIGNED, e.assigneeUserId(), Map.of(
                "processName", en ? info.processEn() : info.processVi(),
                "stepName", en ? info.stepEn() : info.stepVi(),
                "requesterName", info.requester()),
                "core.workflow_task", e.taskId(), "/workflow/tasks/" + e.taskId(),
                "wf-task:" + e.taskId() + ":" + e.assigneeUserId());
    }

    @EventListener
    void on(StepCompleted e) {
        if (!"RETURNED".equals(e.outcome())) {
            return;
        }
        // a request may be returned several times: no dedupe key
        notifyRequester(e.tenantId(), e.instanceId(), CoreNotificationTemplates.REQUEST_RETURNED, null, null);
    }

    @EventListener
    void on(WorkflowCompleted e) {
        notifyRequester(e.tenantId(), e.instanceId(), CoreNotificationTemplates.REQUEST_DECIDED, e.outcome(),
                "wf-decided:" + e.instanceId());
    }

    private void notifyRequester(UUID tenant, UUID instanceId, String event, String outcome, String dedupeKey) {
        record Info(UUID requester, String processVi, String processEn) {
        }
        Info info = jdbc.sql("""
                        select i.requester_user_id, pt.name_vi, pt.name_en from core.workflow_instance i
                          join core.process_type pt on pt.process_type_code = i.process_type_code
                         where i.tenant_id = :t and i.id = :i""")
                .param("t", tenant).param("i", instanceId)
                .query((rs, n) -> new Info(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).single();
        boolean en = english(tenant, info.requester());
        Map<String, Object> payload = outcome == null
                ? Map.of("processName", en ? info.processEn() : info.processVi())
                : Map.of("processName", en ? info.processEn() : info.processVi(), "outcome", outcomeText(outcome, en));
        send(event, info.requester(), payload, "core.workflow_instance", instanceId,
                "/workflow/requests/" + instanceId, dedupeKey);
    }

    private void send(String event, UUID to, Map<String, Object> payload, String refType, UUID refId, String link,
            String dedupeKey) {
        try {
            notifications.send(new SendRequest(event, "core", Recipients.users(to), payload, refType, refId, link,
                    dedupeKey, null));
        } catch (RuntimeException ex) {
            log.warn("Workflow notification {} to {} not sent", event, to, ex);
        }
    }

    private boolean english(UUID tenant, UUID user) {
        return "en-US".equals(jdbc.sql("select locale from core.user_account where tenant_id = :t and id = :u")
                .param("t", tenant).param("u", user).query(String.class).optional().orElse("vi-VN"));
    }

    private static String outcomeText(String outcome, boolean en) {
        return switch (outcome) {
            case "APPROVED" -> en ? "approved" : "đã được duyệt";
            case "REJECTED" -> en ? "rejected" : "bị từ chối";
            case "WITHDRAWN" -> en ? "withdrawn" : "đã rút lại";
            case "CANCELLED" -> en ? "cancelled" : "đã bị hủy";
            default -> outcome;
        };
    }
}
