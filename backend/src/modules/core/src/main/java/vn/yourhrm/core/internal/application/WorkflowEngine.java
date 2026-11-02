package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.ApproverChainResolver.ResolvedStep;
import vn.yourhrm.core.internal.application.WorkflowDefinitionService.Selected;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowAction;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.HistoryEntry;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.StepAssigned;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.StepCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowSubmitted;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowWithdrawn;
import vn.yourhrm.shared.workflow.api.WorkflowService;

/**
 * The table-driven approval engine (ADR-0007; BR-CORE-050/054/056/060/061, US-CORE-052..057). Phase 1 runs
 * sequential steps; a step with several approvers (a role pool or an HR fallback) is decided by the first to act.
 *
 * <p>Every command locks the instance row first, so two actions on one instance never interleave (a concurrent
 * second action finds its task no longer pending and gets 409). Every state change appends to the immutable
 * {@code workflow_action_log} (who, on behalf of whom, when, action, comment, attachments, channel, IP, device).
 * The final outcome is recorded as a {@code workflow_callback} and published as {@link WorkflowCompleted}; the
 * owner module acknowledges it through {@link #acknowledgeCallback}.
 */
@Service
public class WorkflowEngine implements WorkflowService {

    static final Set<String> OPEN = Set.of("IN_PROGRESS", "RETURNED", "INFO_REQUESTED");
    private static final int MAX_CALLBACK_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final CoreJson json;
    private final WorkflowDefinitionService definitions;
    private final ApproverChainResolver resolver;
    private final CoreAuthorizationService authz;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;
    private final TransactionTemplate perTaskTx;

    public WorkflowEngine(JdbcClient jdbc, CoreJson json, WorkflowDefinitionService definitions,
            ApproverChainResolver resolver, CoreAuthorizationService authz, ApplicationEventPublisher events,
            BusinessClock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.definitions = definitions;
        this.resolver = resolver;
        this.authz = authz;
        this.events = events;
        this.clock = clock;
        this.perTaskTx = new TransactionTemplate(transactionManager);
        this.perTaskTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Request metadata recorded in the trail (BR-CORE-061). */
    public record ClientInfo(String ip, String userAgent, String channel) {

        public static ClientInfo api() {
            return new ClientInfo(null, null, "API");
        }
    }

    /** An approver's command on a task. {@code returnTo}: REQUESTER (default) or PREVIOUS. */
    public record TaskCommand(WorkflowAction action, String comment, List<UUID> attachmentIds, UUID forwardTo,
            String returnTo) {

        public TaskCommand {
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }

    public record BulkResult(UUID taskId, boolean approved, String error) {
    }

    /** The instance row as the engine needs it. */
    record Instance(UUID id, String processTypeCode, String ownerModule, String subjectType, UUID subjectId,
            Integer subjectVersion, UUID requester, UUID onBehalfOf, UUID legalEntityId, UUID orgUnitId,
            String status, Integer currentStep, List<ResolvedStep> chain) {

        boolean isRequester(UUID user) {
            return user.equals(requester) || user.equals(onBehalfOf);
        }

        ResolvedStep step(int no) {
            return chain.get(no - 1);
        }
    }

    record Task(UUID id, UUID instanceId, int stepNo, String stepKey, UUID assignee, String status) {
    }

    // ================================================================================================ start

    @Override
    @Transactional
    public StartResult start(StartRequest r) {
        UUID tenant = TenantContext.current().tenantId();
        UUID requester = requireUser();
        Optional<StartResult> existing = jdbc.sql("""
                        select id, status from core.workflow_instance
                         where tenant_id = :t and process_type_code = :p and idempotency_key = :k""")
                .param("t", tenant).param("p", r.processTypeCode()).param("k", r.idempotencyKey())
                .query((rs, n) -> new StartResult(rs.getObject(1, UUID.class), rs.getString(2), false)).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        String ownerModule = jdbc.sql("""
                        select owner_module from core.process_type where process_type_code = :p and is_active""")
                .param("p", r.processTypeCode()).query(String.class).optional()
                .orElseThrow(() -> ApiException.unprocessable("unknown-process-type",
                        "Process type " + r.processTypeCode() + " is not registered or inactive"));
        checkReferences(tenant, r);
        if (jdbc.sql("""
                        select exists (select 1 from core.workflow_instance
                                        where tenant_id = :t and process_type_code = :p and subject_id = :s
                                          and status in ('IN_PROGRESS', 'RETURNED', 'INFO_REQUESTED'))""")
                .param("t", tenant).param("p", r.processTypeCode()).param("s", r.subjectId())
                .query(Boolean.class).single()) {
            throw ApiException.conflict("workflow-already-open",
                    "An approval of this " + r.subjectType() + " is already in progress");
        }
        LocalDate today = clock.today();
        definitions.ensureVendorDefinition(r.processTypeCode());
        Selected definition = definitions.match(r, today).orElseThrow(() -> ApiException.unprocessable(
                "no-workflow-definition", "No workflow definition of " + r.processTypeCode() + " applies"));
        List<ResolvedStep> chain = resolver.resolve(definition.steps(), definition.onResolverFailure(), r, requester,
                today);

        UUID id = Ids.newId();
        jdbc.sql("""
                        insert into core.workflow_instance (tenant_id, id, definition_version_id, process_type_code,
                               owner_module, subject_type, subject_id, subject_version, idempotency_key,
                               requester_user_id, on_behalf_of_user_id, subject_person_id, legal_entity_id, org_unit_id,
                               request_summary, approver_chain_snapshot, status, created_by, updated_by)
                        values (:t, :id, :dv, :p, :m, :st, :s, :sv, :k, :req, :obo, :person, :le, :ou,
                                cast(:summary as jsonb), cast(:chain as jsonb), 'IN_PROGRESS', :req, :req)""")
                .param("t", tenant).param("id", id).param("dv", definition.versionId()).param("p", r.processTypeCode())
                .param("m", ownerModule).param("st", r.subjectType()).param("s", r.subjectId())
                .param("sv", r.subjectVersion()).param("k", r.idempotencyKey()).param("req", requester)
                .param("obo", r.onBehalfOfUserId()).param("person", r.subjectPersonId())
                .param("le", r.legalEntityId()).param("ou", r.orgUnitId())
                .param("summary", json.write(r.requestSummary()))
                .param("chain", json.write(chain.stream().map(ResolvedStep::toMap).toList()))
                .update();
        log(tenant, id, null, "SUBMIT", requester, r.onBehalfOfUserId(), null, "IN_PROGRESS",
                "Definition " + definition.code(), List.of(), ClientInfo.api());
        chain.stream().filter(ResolvedStep::skipped).forEach(s -> log(tenant, id, null, "SKIP", null, null, null, null,
                "Step " + s.stepKey() + " skipped: " + s.reason(), List.of(), ClientInfo.api()));
        events.publishEvent(new WorkflowSubmitted(tenant, id, r.processTypeCode(), r.subjectType(), r.subjectId(),
                requester));
        Instance inst = load(id, false);
        String status = startStepFrom(inst, 1);
        return new StartResult(id, status, true);
    }

    private void checkReferences(UUID tenant, StartRequest r) {
        if (r.legalEntityId() != null && !exists("select 1 from core.legal_entity where tenant_id = :t and id = :id",
                tenant, r.legalEntityId())) {
            throw ApiException.unprocessable("unknown-legal-entity", "Legal entity " + r.legalEntityId() + " not found");
        }
        if (r.orgUnitId() != null && !exists("select 1 from core.org_unit where tenant_id = :t and id = :id", tenant,
                r.orgUnitId())) {
            throw ApiException.unprocessable("unknown-org-unit", "Org unit " + r.orgUnitId() + " not found");
        }
        if (r.onBehalfOfUserId() != null && !exists("select 1 from core.user_account where tenant_id = :t and id = :id",
                tenant, r.onBehalfOfUserId())) {
            throw ApiException.unprocessable("unknown-user", "User " + r.onBehalfOfUserId() + " not found");
        }
    }

    // ================================================================================================ approver actions

    @Transactional
    public void act(UUID taskId, TaskCommand cmd, ClientInfo client) {
        UUID tenant = TenantContext.current().tenantId();
        UUID user = requireUser();
        UUID instanceId = jdbc.sql("select workflow_instance_id from core.workflow_task where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", taskId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Task", taskId));
        Instance inst = load(instanceId, true);
        Task task = task(taskId);
        if (!task.assignee().equals(user)) {
            throw ApiException.notFound("Task", taskId);
        }
        if (!"PENDING".equals(task.status())) {
            throw ApiException.conflict("task-not-pending", "This task was already handled (" + task.status() + ")");
        }
        WorkflowAction action = cmd.action();
        Set<String> allowed = allowedActions(inst.processTypeCode());
        if (action != WorkflowAction.COMMENT && !allowed.contains(action.name())) {
            throw ApiException.unprocessable("action-not-allowed",
                    action + " is not allowed for " + inst.processTypeCode());
        }
        if (action != WorkflowAction.COMMENT && !"IN_PROGRESS".equals(inst.status())) {
            throw ApiException.conflict("instance-not-in-progress", "The request is " + inst.status()
                    + "; the requester must act first");
        }
        checkAttachments(tenant, cmd.attachmentIds());
        switch (action) {
            case APPROVE -> {
                completeTask(task, "APPROVED", user, cmd.comment());
                log(tenant, inst.id(), taskId, "APPROVE", user, null, inst.status(), inst.status(), cmd.comment(),
                        cmd.attachmentIds(), client);
                events.publishEvent(new StepCompleted(tenant, inst.id(), task.stepNo(), task.stepKey(), "APPROVED", user));
                startStepFrom(inst, task.stepNo() + 1);
            }
            case REJECT -> {
                requireComment(cmd, "A reason is required to reject");
                completeTask(task, "REJECTED", user, cmd.comment());
                log(tenant, inst.id(), taskId, "REJECT", user, null, inst.status(), "REJECTED", cmd.comment(),
                        cmd.attachmentIds(), client);
                events.publishEvent(new StepCompleted(tenant, inst.id(), task.stepNo(), task.stepKey(), "REJECTED", user));
                complete(inst, "REJECTED");
            }
            case RETURN -> {
                if (!inst.step(task.stepNo()).allowReturn()) {
                    throw ApiException.unprocessable("action-not-allowed", "This step does not allow returning");
                }
                requireComment(cmd, "Say what has to change when returning a request");
                completeTask(task, "RETURNED", user, cmd.comment());
                events.publishEvent(new StepCompleted(tenant, inst.id(), task.stepNo(), task.stepKey(), "RETURNED", user));
                Optional<ResolvedStep> previous = "PREVIOUS".equalsIgnoreCase(cmd.returnTo())
                        ? inst.chain().stream().filter(s -> s.stepNo() < task.stepNo() && !s.skipped())
                                .reduce((a, b) -> b)
                        : Optional.empty();
                if (previous.isPresent()) {
                    log(tenant, inst.id(), taskId, "RETURN", user, null, inst.status(), inst.status(),
                            "To step " + previous.get().stepKey() + ": " + cmd.comment(), cmd.attachmentIds(), client);
                    startStepFrom(inst, previous.get().stepNo());
                } else {
                    setStatus(inst.id(), "RETURNED", null);
                    log(tenant, inst.id(), taskId, "RETURN", user, null, inst.status(), "RETURNED", cmd.comment(),
                            cmd.attachmentIds(), client);
                }
            }
            case REQUEST_INFO -> {
                requireComment(cmd, "Say which information is needed");
                setStatus(inst.id(), "INFO_REQUESTED", inst.currentStep());
                log(tenant, inst.id(), taskId, "REQUEST_INFO", user, null, inst.status(), "INFO_REQUESTED",
                        cmd.comment(), cmd.attachmentIds(), client);
            }
            case FORWARD -> {
                UUID to = Objects.requireNonNull(cmd.forwardTo(), "forwardTo");
                checkAssignable(tenant, inst, to);
                reassignTask(task, to, "REASSIGN");
                log(tenant, inst.id(), taskId, "FORWARD", user, null, inst.status(), inst.status(),
                        "Forwarded to " + to + (cmd.comment() == null ? "" : ": " + cmd.comment()),
                        cmd.attachmentIds(), client);
                events.publishEvent(new StepAssigned(tenant, inst.id(), taskId, task.stepNo(), task.stepKey(), to,
                        "REASSIGN"));
            }
            case COMMENT -> log(tenant, inst.id(), taskId, cmd.attachmentIds().isEmpty() ? "COMMENT" : "ATTACH", user,
                    null, inst.status(), inst.status(), cmd.comment(), cmd.attachmentIds(), client);
            default -> throw ApiException.unprocessable("action-not-allowed", action + " is not an approver action");
        }
    }

    /** Bulk approve (US-CORE-055): each task in its own transaction, so one failure does not undo the others. */
    public List<BulkResult> bulkApprove(List<UUID> taskIds, String comment, ClientInfo client) {
        List<BulkResult> results = new ArrayList<>();
        for (UUID taskId : taskIds) {
            try {
                perTaskTx.executeWithoutResult(s -> {
                    UUID tenant = TenantContext.current().tenantId();
                    String processType = jdbc.sql("""
                                    select i.process_type_code from core.workflow_task t
                                      join core.workflow_instance i on i.tenant_id = t.tenant_id and i.id = t.workflow_instance_id
                                     where t.tenant_id = :t and t.id = :id""")
                            .param("t", tenant).param("id", taskId).query(String.class).optional()
                            .orElseThrow(() -> ApiException.notFound("Task", taskId));
                    if (!allowedActions(processType).contains(WorkflowAction.BULK_APPROVE.name())) {
                        throw ApiException.unprocessable("bulk-not-allowed",
                                processType + " tasks must be approved one by one");
                    }
                    act(taskId, new TaskCommand(WorkflowAction.APPROVE, comment, List.of(), null, null), client);
                });
                results.add(new BulkResult(taskId, true, null));
            } catch (ApiException e) {
                results.add(new BulkResult(taskId, false, e.code() + ": " + e.getMessage()));
            }
        }
        return results;
    }

    // ================================================================================================ requester actions

    /** WITHDRAW, PROVIDE_INFO, RESUBMIT (after a return) and COMMENT by the requester or a participant. */
    @Transactional
    public void instanceAction(UUID instanceId, WorkflowAction action, String comment, List<UUID> attachments,
            ClientInfo client) {
        UUID tenant = TenantContext.current().tenantId();
        UUID user = requireUser();
        Instance inst = load(instanceId, true);
        List<UUID> docs = attachments == null ? List.of() : attachments;
        checkAttachments(tenant, docs);
        if (action == WorkflowAction.COMMENT) {
            if (!inst.isRequester(user) && !isParticipant(tenant, inst.id(), user)) {
                throw ApiException.notFound("Workflow instance", instanceId);
            }
            log(tenant, inst.id(), null, docs.isEmpty() ? "COMMENT" : "ATTACH", user, null, inst.status(), inst.status(),
                    comment, docs, client);
            return;
        }
        if (!inst.isRequester(user)) {
            throw ApiException.notFound("Workflow instance", instanceId);
        }
        switch (action) {
            case WITHDRAW -> {
                if (!OPEN.contains(inst.status())) {
                    throw ApiException.conflict("instance-closed", "The request is already " + inst.status());
                }
                cancelPendingTasks(inst.id());
                log(tenant, inst.id(), null, "WITHDRAW", user, null, inst.status(), "WITHDRAWN", comment, docs, client);
                events.publishEvent(new WorkflowWithdrawn(tenant, inst.id(), inst.processTypeCode(), inst.subjectId()));
                complete(inst, "WITHDRAWN");
            }
            case PROVIDE_INFO -> {
                if (!"INFO_REQUESTED".equals(inst.status())) {
                    throw ApiException.conflict("instance-not-waiting", "No information was requested");
                }
                setStatus(inst.id(), "IN_PROGRESS", inst.currentStep());
                log(tenant, inst.id(), null, "PROVIDE_INFO", user, null, inst.status(), "IN_PROGRESS", comment, docs,
                        client);
            }
            case RESUBMIT -> {
                if (!"RETURNED".equals(inst.status())) {
                    throw ApiException.conflict("instance-not-returned", "Only a returned request can be resubmitted");
                }
                log(tenant, inst.id(), null, "SUBMIT", user, null, inst.status(), "IN_PROGRESS", comment, docs, client);
                setStatus(inst.id(), "IN_PROGRESS", null);
                startStepFrom(load(inst.id(), false), 1);
            }
            default -> throw ApiException.unprocessable("action-not-allowed", action + " is not a requester action");
        }
    }

    // ================================================================================================ administration

    /** Reassigns one open task with a mandatory reason (BR-CORE-056, US-CORE-057). */
    @Transactional
    public void reassign(UUID taskId, UUID toUser, String reason, ClientInfo client) {
        UUID tenant = TenantContext.current().tenantId();
        UUID admin = requireUser();
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason-required", "A reason is required to reassign a task");
        }
        UUID instanceId = jdbc.sql("select workflow_instance_id from core.workflow_task where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", taskId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Task", taskId));
        Instance inst = load(instanceId, true);
        if (!authz.can(CorePermissions.WORKFLOW_REASSIGN, target(inst))) {
            throw ApiException.notFound("Task", taskId);
        }
        Task task = task(taskId);
        if (!"PENDING".equals(task.status())) {
            throw ApiException.conflict("task-not-pending", "This task was already handled (" + task.status() + ")");
        }
        checkAssignable(tenant, inst, toUser);
        reassignTask(task, toUser, "REASSIGN");
        log(tenant, inst.id(), taskId, "REASSIGN", admin, null, inst.status(), inst.status(),
                "Reassigned from " + task.assignee() + " to " + toUser + ": " + reason, List.of(), client);
        events.publishEvent(new StepAssigned(tenant, inst.id(), taskId, task.stepNo(), task.stepKey(), toUser,
                "REASSIGN"));
    }

    /** Reassigns every open task of {@code fromUser} that the caller may reassign; returns the task ids moved. */
    @Transactional
    public List<UUID> reassignAll(UUID fromUser, UUID toUser, String reason, ClientInfo client) {
        UUID tenant = TenantContext.current().tenantId();
        List<UUID> tasks = jdbc.sql("""
                        select id from core.workflow_task where tenant_id = :t and assignee_user_id = :u and status = 'PENDING'
                         order by created_at""")
                .param("t", tenant).param("u", fromUser).query(UUID.class).list();
        List<UUID> moved = new ArrayList<>();
        for (UUID task : tasks) {
            try {
                reassign(task, toUser, reason, client);
                moved.add(task);
            } catch (ApiException e) {
                if (e.status() != HttpStatus.NOT_FOUND) {
                    throw e;
                }
            }
        }
        return moved;
    }

    /** Cancels an open instance with a reason (workflow console, US-CORE-061). */
    @Transactional
    public void cancel(UUID instanceId, String reason, ClientInfo client) {
        UUID tenant = TenantContext.current().tenantId();
        UUID admin = requireUser();
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason-required", "A reason is required to cancel a request");
        }
        Instance inst = load(instanceId, true);
        if (!OPEN.contains(inst.status())) {
            throw ApiException.conflict("instance-closed", "The request is already " + inst.status());
        }
        cancelPendingTasks(inst.id());
        log(tenant, inst.id(), null, "CANCEL", admin, null, inst.status(), "CANCELLED", reason, List.of(), client);
        complete(inst, "CANCELLED");
    }

    // ================================================================================================ callbacks

    @Override
    @Transactional
    public void acknowledgeCallback(UUID callbackId, Map<String, Object> moduleResult) {
        UUID tenant = TenantContext.current().tenantId();
        int updated = jdbc.sql("""
                        update core.workflow_callback
                           set status = 'ACKED', acked_at = now(), module_result = cast(:r as jsonb), last_error = null
                         where tenant_id = :t and id = :id and status <> 'ACKED'""")
                .param("r", json.write(moduleResult == null ? Map.of() : moduleResult))
                .param("t", tenant).param("id", callbackId).update();
        if (updated == 0 && !exists("select 1 from core.workflow_callback where tenant_id = :t and id = :id", tenant,
                callbackId)) {
            throw ApiException.notFound("Workflow callback", callbackId);
        }
    }

    @Override
    @Transactional
    public void reportCallbackFailure(UUID callbackId, String error) {
        UUID tenant = TenantContext.current().tenantId();
        int updated = jdbc.sql("""
                        update core.workflow_callback
                           set attempts = attempts + 1, last_error = :e,
                               status = case when attempts + 1 >= :max then 'DEAD' else 'FAILED' end
                         where tenant_id = :t and id = :id and status <> 'ACKED'""")
                .param("e", error).param("max", MAX_CALLBACK_ATTEMPTS).param("t", tenant).param("id", callbackId)
                .update();
        if (updated == 0 && !exists("select 1 from core.workflow_callback where tenant_id = :t and id = :id", tenant,
                callbackId)) {
            throw ApiException.notFound("Workflow callback", callbackId);
        }
    }

    /** Publishes {@link WorkflowCompleted} again for a failed / unacknowledged callback (workflow console). */
    @Transactional
    public void retryCallback(UUID callbackId) {
        UUID tenant = TenantContext.current().tenantId();
        record Callback(UUID instanceId, String outcome, String status) {
        }
        Callback cb = jdbc.sql("""
                        select workflow_instance_id, outcome, status from core.workflow_callback
                         where tenant_id = :t and id = :id for update""")
                .param("t", tenant).param("id", callbackId)
                .query((rs, n) -> new Callback(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional().orElseThrow(() -> ApiException.notFound("Workflow callback", callbackId));
        if ("ACKED".equals(cb.status())) {
            throw ApiException.conflict("callback-acked", "The owner module already applied this outcome");
        }
        jdbc.sql("update core.workflow_callback set status = 'PUBLISHED' where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", callbackId).update();
        publishCompleted(load(cb.instanceId(), false), callbackId, cb.outcome());
    }

    // ================================================================================================ internals

    /** Opens the first non-skipped step at or after {@code from}; completes APPROVED when none is left. */
    private String startStepFrom(Instance inst, int from) {
        UUID tenant = TenantContext.current().tenantId();
        Optional<ResolvedStep> next = inst.chain().stream().filter(s -> s.stepNo() >= from && !s.skipped()).findFirst();
        if (next.isEmpty()) {
            complete(inst, "APPROVED");
            return "APPROVED";
        }
        ResolvedStep step = next.get();
        String type = step.approvers().size() == 1 ? "SINGLE" : "ANY_OF";
        for (UUID approver : step.approvers()) {
            UUID taskId = Ids.newId();
            jdbc.sql("""
                            insert into core.workflow_task (tenant_id, id, workflow_instance_id, step_no, step_key, step_type,
                                   assignee_user_id, assigned_via)
                            values (:t, :id, :i, :n, :k, :type, :a, :via)""")
                    .param("t", tenant).param("id", taskId).param("i", inst.id()).param("n", step.stepNo())
                    .param("k", step.stepKey()).param("type", type).param("a", approver)
                    .param("via", "FALLBACK".equals(step.via()) ? "FALLBACK" : "RESOLVER")
                    .update();
            events.publishEvent(new StepAssigned(tenant, inst.id(), taskId, step.stepNo(), step.stepKey(), approver,
                    "FALLBACK".equals(step.via()) ? "FALLBACK" : "RESOLVER"));
        }
        setStatus(inst.id(), "IN_PROGRESS", step.stepNo());
        return "IN_PROGRESS";
    }

    /** Final outcome: status, callback record, {@link WorkflowCompleted} (BR-CORE-060, US-CORE-052). */
    private void complete(Instance inst, String outcome) {
        UUID tenant = TenantContext.current().tenantId();
        jdbc.sql("""
                        update core.workflow_instance
                           set status = :o, outcome = :o, completed_at = now(), current_step_no = null,
                               updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("o", outcome).param("actor", TenantContext.current().userAccountId())
                .param("t", tenant).param("id", inst.id()).update();
        log(tenant, inst.id(), null, "COMPLETE", null, null, inst.status(), outcome, "Outcome " + outcome, List.of(),
                ClientInfo.api());
        UUID callbackId = Ids.newId();
        jdbc.sql("""
                        insert into core.workflow_callback (tenant_id, id, workflow_instance_id, target_module,
                               callback_event_type, outcome, idempotency_key, status)
                        values (:t, :id, :i, :m, 'core.WorkflowCompleted', :o, :k, 'PUBLISHED')""")
                .param("t", tenant).param("id", callbackId).param("i", inst.id()).param("m", inst.ownerModule())
                .param("o", outcome).param("k", inst.id() + ":COMPLETED").update();
        publishCompleted(inst, callbackId, outcome);
    }

    private void publishCompleted(Instance inst, UUID callbackId, String outcome) {
        UUID tenant = TenantContext.current().tenantId();
        events.publishEvent(new WorkflowCompleted(tenant, inst.id(), callbackId, inst.processTypeCode(),
                inst.ownerModule(), inst.subjectType(), inst.subjectId(), inst.subjectVersion(), outcome,
                history(tenant, inst.id())));
    }

    List<HistoryEntry> history(UUID tenant, UUID instanceId) {
        return jdbc.sql("""
                        select l.occurred_at, l.action, l.actor_user_id, l.on_behalf_of_user_id, t.step_no, l.comment
                          from core.workflow_action_log l
                          left join core.workflow_task t on t.tenant_id = l.tenant_id and t.id = l.workflow_task_id
                         where l.tenant_id = :t and l.workflow_instance_id = :i
                         order by l.occurred_at, l.id""")
                .param("t", tenant).param("i", instanceId)
                .query((rs, n) -> new HistoryEntry(rs.getTimestamp(1).toInstant(), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), (Integer) rs.getObject(5),
                        rs.getString(6)))
                .list();
    }

    private void completeTask(Task task, String outcome, UUID actor, String comment) {
        UUID tenant = TenantContext.current().tenantId();
        jdbc.sql("""
                        update core.workflow_task
                           set status = 'COMPLETED', outcome = :o, acted_by_user_id = :a, acted_at = now(), comment = :c,
                               updated_by = :a, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("o", outcome).param("a", actor).param("c", comment).param("t", tenant).param("id", task.id())
                .update();
        // the other members of a pool are released: the first to act decides the step
        jdbc.sql("""
                        update core.workflow_task set status = 'CANCELLED', updated_by = :a, version = version + 1
                         where tenant_id = :t and workflow_instance_id = :i and step_no = :n and status = 'PENDING'""")
                .param("a", actor).param("t", tenant).param("i", task.instanceId()).param("n", task.stepNo()).update();
    }

    private void cancelPendingTasks(UUID instanceId) {
        jdbc.sql("""
                        update core.workflow_task set status = 'CANCELLED', updated_by = :a, version = version + 1
                         where tenant_id = :t and workflow_instance_id = :i and status = 'PENDING'""")
                .param("a", TenantContext.current().userAccountId()).param("t", TenantContext.current().tenantId())
                .param("i", instanceId).update();
    }

    private void reassignTask(Task task, UUID to, String via) {
        jdbc.sql("""
                        update core.workflow_task
                           set original_assignee_user_id = coalesce(original_assignee_user_id, assignee_user_id),
                               assignee_user_id = :to, assigned_via = :via, updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("to", to).param("via", via).param("actor", TenantContext.current().userAccountId())
                .param("t", TenantContext.current().tenantId()).param("id", task.id()).update();
    }

    private void setStatus(UUID instanceId, String status, Integer currentStep) {
        jdbc.sql("""
                        update core.workflow_instance set status = :s, current_step_no = :n, updated_by = :actor,
                               version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("s", status).param("n", currentStep).param("actor", TenantContext.current().userAccountId())
                .param("t", TenantContext.current().tenantId()).param("id", instanceId).update();
    }

    /**
     * The new assignee of a forwarded or reassigned task must be an active user other than the requester and must
     * see the request's organisation (data scope of org units / legal entities, US-CORE-057).
     */
    private void checkAssignable(UUID tenant, Instance inst, UUID to) {
        if (inst.isRequester(to)) {
            throw ApiException.unprocessable("invalid-assignee", "The requester cannot approve their own request");
        }
        if (!exists("""
                        select 1 from core.user_account where tenant_id = :t and id = :id
                           and status = 'ACTIVE' and (expires_at is null or expires_at > now())""", tenant, to)) {
            throw ApiException.unprocessable("invalid-assignee", "User " + to + " is not an active user");
        }
        AuthorizationTarget target = target(inst);
        String permission = inst.orgUnitId() != null ? CorePermissions.ORG_UNIT_VIEW : CorePermissions.LEGAL_ENTITY_VIEW;
        if (!(target instanceof AuthorizationTarget.Tenant) && !authz.usersWhoCan(permission, target, clock.today())
                .contains(to)) {
            throw ApiException.unprocessable("assignee-out-of-scope",
                    "User " + to + " has no data scope over this request's organisation");
        }
    }

    private static AuthorizationTarget target(Instance inst) {
        if (inst.orgUnitId() != null) {
            return new AuthorizationTarget.OrgUnit(inst.orgUnitId());
        }
        if (inst.legalEntityId() != null) {
            return new AuthorizationTarget.LegalEntity(inst.legalEntityId());
        }
        return AuthorizationTarget.tenant();
    }

    private void checkAttachments(UUID tenant, List<UUID> docs) {
        for (UUID doc : docs) {
            if (!exists("select 1 from core.document where tenant_id = :t and id = :id and status = 'ACTIVE'", tenant,
                    doc)) {
                throw ApiException.unprocessable("unknown-document", "Attachment " + doc + " not found");
            }
        }
    }

    private static void requireComment(TaskCommand cmd, String message) {
        if (cmd.comment() == null || cmd.comment().isBlank()) {
            throw ApiException.unprocessable("comment-required", message);
        }
    }

    Set<String> allowedActions(String processTypeCode) {
        return jdbc.sql("select allowed_actions from core.process_type where process_type_code = :p")
                .param("p", processTypeCode)
                .query((rs, n) -> Set.of((String[]) rs.getArray(1).getArray())).optional().orElse(Set.of());
    }

    boolean isParticipant(UUID tenant, UUID instanceId, UUID user) {
        return jdbc.sql("""
                        select exists (select 1 from core.workflow_task where tenant_id = :t and workflow_instance_id = :i
                                          and (assignee_user_id = :u or original_assignee_user_id = :u
                                               or acted_by_user_id = :u))""")
                .param("t", tenant).param("i", instanceId).param("u", user).query(Boolean.class).single();
    }

    Instance load(UUID instanceId, boolean lock) {
        UUID tenant = TenantContext.current().tenantId();
        return jdbc.sql("""
                        select id, process_type_code, owner_module, subject_type, subject_id, subject_version,
                               requester_user_id, on_behalf_of_user_id, legal_entity_id, org_unit_id, status,
                               current_step_no, approver_chain_snapshot::text
                          from core.workflow_instance where tenant_id = :t and id = :id""" + (lock ? " for update" : ""))
                .param("t", tenant).param("id", instanceId)
                .query((rs, n) -> new Instance(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), (Integer) rs.getObject(6),
                        rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
                        rs.getObject(10, UUID.class), rs.getString(11), (Integer) rs.getObject(12),
                        json.listOfMaps(rs.getString(13)).stream().map(ResolvedStep::fromMap).toList()))
                .optional().orElseThrow(() -> ApiException.notFound("Workflow instance", instanceId));
    }

    private Task task(UUID taskId) {
        return jdbc.sql("""
                        select id, workflow_instance_id, step_no, step_key, assignee_user_id, status
                          from core.workflow_task where tenant_id = :t and id = :id""")
                .param("t", TenantContext.current().tenantId()).param("id", taskId)
                .query((rs, n) -> new Task(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6)))
                .optional().orElseThrow(() -> ApiException.notFound("Task", taskId));
    }

    /** Appends to the immutable trail (BR-CORE-061). {@code actor} null = SYSTEM. */
    private void log(UUID tenant, UUID instanceId, UUID taskId, String action, UUID actor, UUID onBehalfOf,
            String from, String to, String comment, List<UUID> attachments, ClientInfo client) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", tenant);
        p.put("id", Ids.newId());
        p.put("i", instanceId);
        p.put("task", taskId);
        p.put("action", action);
        p.put("type", actor == null ? "SYSTEM" : "USER");
        p.put("actor", actor);
        p.put("obo", onBehalfOf);
        p.put("from", from);
        p.put("to", to);
        p.put("comment", comment);
        p.put("docs", attachments.stream().map(UUID::toString).toArray(String[]::new));
        p.put("channel", actor == null ? "SYSTEM" : client.channel());
        p.put("ip", client.ip());
        p.put("ua", client.userAgent());
        jdbc.sql("""
                        insert into core.workflow_action_log (tenant_id, id, workflow_instance_id, workflow_task_id, action,
                               actor_type, actor_user_id, on_behalf_of_user_id, from_status, to_status, comment,
                               attachment_doc_ids, channel, client_ip, user_agent, occurred_at)
                        values (:t, :id, :i, :task, :action, :type, :actor, :obo, :from, :to, :comment,
                                cast(cast(:docs as text[]) as uuid[]), :channel, cast(:ip as inet), :ua, clock_timestamp())""")
                .params(p).update();
    }

    private boolean exists(String sql, UUID tenant, UUID id) {
        return jdbc.sql("select exists (" + sql + ")").param("t", tenant).param("id", id).query(Boolean.class).single();
    }

    private static UUID requireUser() {
        UUID user = TenantContext.current().userAccountId();
        if (user == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "no-user-account", "Approvals need a tenant user account");
        }
        return user;
    }
}
