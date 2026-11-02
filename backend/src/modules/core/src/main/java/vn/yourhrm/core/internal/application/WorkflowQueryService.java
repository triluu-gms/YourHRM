package vn.yourhrm.core.internal.application;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.ApproverChainResolver.ResolvedStep;
import vn.yourhrm.core.internal.application.WorkflowEngine.Instance;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * The unified inbox (BR-CORE-060, US-CORE-059: "My tasks / My requests" across modules), the request detail with
 * the caller's allowed actions, the printable approval trail (BR-CORE-061, US-CORE-060) and the workflow console
 * (US-CORE-061). A request is visible to its requester / subject, to everyone who held one of its tasks, and to
 * holders of {@code core.workflow.view} whose scope covers its organisation.
 */
@Service
public class WorkflowQueryService {

    private final JdbcClient jdbc;
    private final CoreJson json;
    private final WorkflowEngine engine;
    private final CoreAuthorizationService authz;

    public WorkflowQueryService(JdbcClient jdbc, CoreJson json, WorkflowEngine engine,
            CoreAuthorizationService authz) {
        this.jdbc = jdbc;
        this.json = json;
        this.engine = engine;
        this.authz = authz;
    }

    public record TaskItem(UUID taskId, UUID instanceId, String processTypeCode, String processNameVi,
            String processNameEn, int stepNo, String stepKey, String stepNameVi, UUID requesterUserId,
            String requesterName, Instant submittedAt, Instant assignedAt, String instanceStatus,
            Map<String, Object> summary) {
    }

    public record RequestItem(UUID instanceId, String processTypeCode, String processNameVi, String processNameEn,
            String subjectType, UUID subjectId, String status, String outcome, Integer currentStep,
            Instant submittedAt, Instant completedAt, Map<String, Object> summary) {
    }

    public record TaskView(UUID taskId, int stepNo, String stepKey, String stepType, UUID assigneeUserId,
            UUID originalAssigneeUserId, String assignedVia, String status, String outcome, UUID actedByUserId,
            Instant actedAt, String comment) {
    }

    public record TrailEntry(Instant occurredAt, String action, String actorType, UUID actorUserId, String actorName,
            UUID onBehalfOfUserId, Integer stepNo, String fromStatus, String toStatus, String comment,
            List<UUID> attachmentIds, String channel, String clientIp, String userAgent) {
    }

    public record InstanceDetail(UUID instanceId, String processTypeCode, String ownerModule, String subjectType,
            UUID subjectId, UUID requesterUserId, UUID onBehalfOfUserId, UUID legalEntityId, UUID orgUnitId,
            String status, String outcome, Integer currentStep, Instant submittedAt, Instant completedAt,
            Map<String, Object> summary, List<ResolvedStep> approverChain, List<TaskView> tasks,
            List<TrailEntry> trail, Set<String> allowedActions, UUID myPendingTaskId) {
    }

    public record ConsoleItem(UUID instanceId, String processTypeCode, String status, Instant submittedAt,
            long ageHours, UUID requesterUserId, String callbackStatus, String lastError) {
    }

    public record CallbackItem(UUID callbackId, UUID instanceId, String targetModule, String outcome, String status,
            int attempts, String lastError, Instant createdAt) {
    }

    // ------------------------------------------------------------------------------------ inbox

    @Transactional(readOnly = true)
    public List<TaskItem> myTasks(String processTypeCode) {
        return jdbc.sql("""
                        select t.id, i.id, i.process_type_code, pt.name_vi, pt.name_en, t.step_no, t.step_key,
                               i.approver_chain_snapshot -> (t.step_no - 1) ->> 'nameVi', i.requester_user_id,
                               coalesce(u.display_name, u.login), i.submitted_at, t.created_at, i.status,
                               i.request_summary::text
                          from core.workflow_task t
                          join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                          join core.process_type pt on pt.process_type_code = i.process_type_code
                          join core.user_account u on u.tenant_id = i.tenant_id and u.id = i.requester_user_id
                         where t.tenant_id = :t and t.assignee_user_id = :u and t.status = 'PENDING'
                           and (cast(:p as text) is null or i.process_type_code = :p)
                         order by t.created_at""")
                .param("t", tenant()).param("u", user()).param("p", processTypeCode)
                .query((rs, n) -> new TaskItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7),
                        rs.getString(8), rs.getObject(9, UUID.class), rs.getString(10), instant(rs, 11),
                        instant(rs, 12), rs.getString(13), json.map(rs.getString(14))))
                .list();
    }

    /** Header counters: pending tasks per process type. */
    @Transactional(readOnly = true)
    public Map<String, Long> myTaskCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("""
                        select i.process_type_code, count(*) from core.workflow_task t
                          join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                         where t.tenant_id = :t and t.assignee_user_id = :u and t.status = 'PENDING'
                         group by 1 order by 1""")
                .param("t", tenant()).param("u", user())
                .query(rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    @Transactional(readOnly = true)
    public List<RequestItem> myRequests(String status) {
        return jdbc.sql("""
                        select i.id, i.process_type_code, pt.name_vi, pt.name_en, i.subject_type, i.subject_id, i.status,
                               i.outcome, i.current_step_no, i.submitted_at, i.completed_at, i.request_summary::text
                          from core.workflow_instance i
                          join core.process_type pt on pt.process_type_code = i.process_type_code
                         where i.tenant_id = :t and (i.requester_user_id = :u or i.on_behalf_of_user_id = :u)
                           and (cast(:s as text) is null or i.status = :s)
                         order by i.submitted_at desc""")
                .param("t", tenant()).param("u", user()).param("s", status)
                .query((rs, n) -> new RequestItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8),
                        (Integer) rs.getObject(9), instant(rs, 10), instant(rs, 11), json.map(rs.getString(12))))
                .list();
    }

    @Transactional(readOnly = true)
    public InstanceDetail detail(UUID instanceId) {
        UUID tenant = tenant();
        UUID user = user();
        Instance inst = engine.load(instanceId, false);
        boolean requester = inst.isRequester(user);
        boolean participant = engine.isParticipant(tenant, inst.id(), user);
        if (!requester && !participant && !authz.can(CorePermissions.WORKFLOW_VIEW, target(inst))) {
            throw ApiException.notFound("Workflow instance", instanceId);
        }
        record Head(String outcome, Instant submittedAt, Instant completedAt, String summary) {
        }
        Head head = jdbc.sql("""
                        select outcome, submitted_at, completed_at, request_summary::text from core.workflow_instance
                         where tenant_id = :t and id = :id""")
                .param("t", tenant).param("id", instanceId)
                .query((rs, n) -> new Head(rs.getString(1), instant(rs, 2), instant(rs, 3), rs.getString(4))).single();
        List<TaskView> tasks = jdbc.sql("""
                        select id, step_no, step_key, step_type, assignee_user_id, original_assignee_user_id, assigned_via,
                               status, outcome, acted_by_user_id, acted_at, comment
                          from core.workflow_task where tenant_id = :t and workflow_instance_id = :i
                         order by created_at, id""")
                .param("t", tenant).param("i", instanceId)
                .query((rs, n) -> new TaskView(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class), instant(rs, 11),
                        rs.getString(12)))
                .list();
        UUID myTask = tasks.stream().filter(t -> "PENDING".equals(t.status()) && user.equals(t.assigneeUserId()))
                .map(TaskView::taskId).findFirst().orElse(null);
        return new InstanceDetail(inst.id(), inst.processTypeCode(), inst.ownerModule(), inst.subjectType(),
                inst.subjectId(), inst.requester(), inst.onBehalfOf(), inst.legalEntityId(), inst.orgUnitId(),
                inst.status(), head.outcome(), inst.currentStep(), head.submittedAt(), head.completedAt(),
                json.map(head.summary()), inst.chain(), tasks, trail(instanceId), allowed(inst, myTask, requester,
                        participant), myTask);
    }

    /** The printable approval trail (BR-CORE-061); rendering to PDF goes through the document service (US-CORE-103). */
    @Transactional(readOnly = true)
    public List<TrailEntry> trailFor(UUID instanceId) {
        return detail(instanceId).trail();
    }

    private List<TrailEntry> trail(UUID instanceId) {
        return jdbc.sql("""
                        select l.occurred_at, l.action, l.actor_type, l.actor_user_id, coalesce(u.display_name, u.login),
                               l.on_behalf_of_user_id, t.step_no, l.from_status, l.to_status, l.comment,
                               l.attachment_doc_ids, l.channel, host(l.client_ip), l.user_agent
                          from core.workflow_action_log l
                          left join core.user_account u on u.tenant_id = l.tenant_id and u.id = l.actor_user_id
                          left join core.workflow_task t on t.tenant_id = l.tenant_id and t.id = l.workflow_task_id
                         where l.tenant_id = :t and l.workflow_instance_id = :i
                         order by l.occurred_at, l.id""")
                .param("t", tenant()).param("i", instanceId)
                .query((rs, n) -> new TrailEntry(instant(rs, 1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, UUID.class),
                        (Integer) rs.getObject(7), rs.getString(8), rs.getString(9), rs.getString(10), uuids(rs, 11),
                        rs.getString(12), rs.getString(13), rs.getString(14)))
                .list();
    }

    private Set<String> allowed(Instance inst, UUID myTask, boolean requester, boolean participant) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> typeActions = engine.allowedActions(inst.processTypeCode());
        if (myTask != null && "IN_PROGRESS".equals(inst.status())) {
            out.add(WorkflowAction.APPROVE.name());
            out.add(WorkflowAction.REJECT.name());
            if (typeActions.contains("RETURN") && inst.currentStep() != null
                    && inst.step(inst.currentStep()).allowReturn()) {
                out.add(WorkflowAction.RETURN.name());
            }
            for (String a : List.of("REQUEST_INFO", "FORWARD")) {
                if (typeActions.contains(a)) {
                    out.add(a);
                }
            }
        }
        if (requester) {
            if (WorkflowEngine.OPEN.contains(inst.status())) {
                out.add(WorkflowAction.WITHDRAW.name());
            }
            if ("INFO_REQUESTED".equals(inst.status())) {
                out.add(WorkflowAction.PROVIDE_INFO.name());
            }
            if ("RETURNED".equals(inst.status())) {
                out.add(WorkflowAction.RESUBMIT.name());
            }
        }
        if (requester || participant) {
            out.add(WorkflowAction.COMMENT.name());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ console

    @Transactional(readOnly = true)
    public List<ConsoleItem> console(String status, String processTypeCode, Integer olderThanHours) {
        return jdbc.sql("""
                        select i.id, i.process_type_code, i.status, i.submitted_at,
                               floor(extract(epoch from now() - i.submitted_at) / 3600)::bigint, i.requester_user_id,
                               c.status, c.last_error
                          from core.workflow_instance i
                          left join core.workflow_callback c on c.tenant_id = i.tenant_id and c.workflow_instance_id = i.id
                         where i.tenant_id = :t
                           and (cast(:s as text) is null or i.status = :s)
                           and (cast(:p as text) is null or i.process_type_code = :p)
                           and (cast(:h as int) is null or i.submitted_at < now() - make_interval(hours => cast(:h as int)))
                         order by i.submitted_at""")
                .param("t", tenant()).param("s", status).param("p", processTypeCode).param("h", olderThanHours)
                .query((rs, n) -> new ConsoleItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        instant(rs, 4), rs.getLong(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8)))
                .list();
    }

    /** Callbacks the owner module has not acknowledged (the "error tasks" of US-CORE-052 / US-CORE-061). */
    @Transactional(readOnly = true)
    public List<CallbackItem> openCallbacks() {
        return jdbc.sql("""
                        select id, workflow_instance_id, target_module, outcome, status, attempts, last_error, created_at
                          from core.workflow_callback where tenant_id = :t and status <> 'ACKED' order by created_at""")
                .param("t", tenant())
                .query((rs, n) -> new CallbackItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7),
                        instant(rs, 8)))
                .list();
    }

    // ------------------------------------------------------------------------------------ helpers

    private static AuthorizationTarget target(Instance inst) {
        if (inst.orgUnitId() != null) {
            return new AuthorizationTarget.OrgUnit(inst.orgUnitId());
        }
        if (inst.legalEntityId() != null) {
            return new AuthorizationTarget.LegalEntity(inst.legalEntityId());
        }
        return AuthorizationTarget.tenant();
    }

    private static List<UUID> uuids(ResultSet rs, int column) throws SQLException {
        List<UUID> out = new ArrayList<>();
        Array a = rs.getArray(column);
        if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
                out.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
            }
        }
        return out;
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID user() {
        UUID user = TenantContext.current().userAccountId();
        if (user == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "no-user-account", "The inbox needs a tenant user account");
        }
        return user;
    }
}
