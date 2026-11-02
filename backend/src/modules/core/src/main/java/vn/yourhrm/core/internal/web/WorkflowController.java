package vn.yourhrm.core.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.WorkflowEngine;
import vn.yourhrm.core.internal.application.WorkflowEngine.BulkResult;
import vn.yourhrm.core.internal.application.WorkflowEngine.ClientInfo;
import vn.yourhrm.core.internal.application.WorkflowEngine.TaskCommand;
import vn.yourhrm.core.internal.application.WorkflowQueryService;
import vn.yourhrm.core.internal.application.WorkflowQueryService.CallbackItem;
import vn.yourhrm.core.internal.application.WorkflowQueryService.ConsoleItem;
import vn.yourhrm.core.internal.application.WorkflowQueryService.InstanceDetail;
import vn.yourhrm.core.internal.application.WorkflowQueryService.RequestItem;
import vn.yourhrm.core.internal.application.WorkflowQueryService.TaskItem;
import vn.yourhrm.core.internal.application.WorkflowQueryService.TrailEntry;
import vn.yourhrm.shared.auth.api.PublicEndpoint;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.workflow.api.WorkflowAction;

/**
 * The approval inbox and actions for every user (BR-CORE-054/060/061): the service authorises per task / request
 * (assignee, requester, participant, or {@code core.workflow.view} in scope). Reassignment and the console need
 * workflow administration permissions.
 */
@RestController
@RequestMapping("/api/v1/core/workflow")
class WorkflowController {

    private final WorkflowEngine engine;
    private final WorkflowQueryService queries;

    WorkflowController(WorkflowEngine engine, WorkflowQueryService queries) {
        this.engine = engine;
        this.queries = queries;
    }

    record TaskActionRequest(@NotNull WorkflowAction action, @Size(max = 4000) String comment,
            List<UUID> attachmentIds, UUID forwardTo, String returnTo) {
    }

    record InstanceActionRequest(@NotNull WorkflowAction action, @Size(max = 4000) String comment,
            List<UUID> attachmentIds) {
    }

    record BulkApproveRequest(@NotEmpty @Size(max = 200) List<UUID> taskIds, @Size(max = 4000) String comment) {
    }

    record ReassignRequest(@NotNull UUID toUserId, @NotBlank @Size(max = 1000) String reason) {
    }

    record ReassignAllRequest(@NotNull UUID fromUserId, @NotNull UUID toUserId,
            @NotBlank @Size(max = 1000) String reason) {
    }

    record CancelRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    private static ClientInfo client(HttpServletRequest request) {
        return ClientInfoResolver.web(request);
    }

    // ------------------------------------------------------------------------------------ my tasks / my requests

    @GetMapping("/tasks")
    @PublicEndpoint(reason = "Every user sees the tasks assigned to them")
    public List<TaskItem> myTasks(@RequestParam(name = "processType", required = false) String processType) {
        return queries.myTasks(processType);
    }

    @GetMapping("/tasks/counts")
    @PublicEndpoint(reason = "Header counter of the caller's own tasks")
    public Map<String, Long> myTaskCounts() {
        return queries.myTaskCounts();
    }

    @PostMapping("/tasks/{taskId}/actions")
    @PublicEndpoint(reason = "Only the task's assignee may act; checked by the engine")
    public ResponseEntity<Void> act(@PathVariable UUID taskId, @Valid @RequestBody TaskActionRequest req,
            HttpServletRequest request) {
        engine.act(taskId, new TaskCommand(req.action(), req.comment(), req.attachmentIds(), req.forwardTo(),
                req.returnTo()), client(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/bulk-approve")
    @PublicEndpoint(reason = "Only the tasks' assignee may act; checked per task by the engine")
    public List<BulkResult> bulkApprove(@Valid @RequestBody BulkApproveRequest req, HttpServletRequest request) {
        return engine.bulkApprove(req.taskIds(), req.comment(), client(request));
    }

    @GetMapping("/requests")
    @PublicEndpoint(reason = "Every user sees the requests they submitted or that are about them")
    public List<RequestItem> myRequests(@RequestParam(name = "status", required = false) String status) {
        return queries.myRequests(status);
    }

    @GetMapping("/instances/{instanceId}")
    @PublicEndpoint(reason = "Visible to the requester, participants and core.workflow.view in scope; checked by the service")
    public InstanceDetail detail(@PathVariable UUID instanceId) {
        return queries.detail(instanceId);
    }

    @GetMapping("/instances/{instanceId}/trail")
    @PublicEndpoint(reason = "Same visibility as the request; checked by the service")
    public List<TrailEntry> trail(@PathVariable UUID instanceId) {
        return queries.trailFor(instanceId);
    }

    @PostMapping("/instances/{instanceId}/actions")
    @PublicEndpoint(reason = "Requester actions and comments; the engine checks the caller's role in the request")
    public ResponseEntity<Void> instanceAction(@PathVariable UUID instanceId,
            @Valid @RequestBody InstanceActionRequest req, HttpServletRequest request) {
        engine.instanceAction(instanceId, req.action(), req.comment(), req.attachmentIds(), client(request));
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------------------ administration

    @PostMapping("/tasks/{taskId}/reassign")
    @RequiresPermission(CorePermissions.WORKFLOW_REASSIGN)
    public ResponseEntity<Void> reassign(@PathVariable UUID taskId, @Valid @RequestBody ReassignRequest req,
            HttpServletRequest request) {
        engine.reassign(taskId, req.toUserId(), req.reason(), client(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reassignments")
    @RequiresPermission(CorePermissions.WORKFLOW_REASSIGN)
    public List<UUID> reassignAll(@Valid @RequestBody ReassignAllRequest req, HttpServletRequest request) {
        return engine.reassignAll(req.fromUserId(), req.toUserId(), req.reason(), client(request));
    }

    @PostMapping("/instances/{instanceId}/cancel")
    @RequiresPermission(value = CorePermissions.WORKFLOW_ADMIN, tenantWide = true)
    public ResponseEntity<Void> cancel(@PathVariable UUID instanceId, @Valid @RequestBody CancelRequest req,
            HttpServletRequest request) {
        engine.cancel(instanceId, req.reason(), client(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/console/instances")
    @RequiresPermission(value = CorePermissions.WORKFLOW_ADMIN, tenantWide = true)
    public List<ConsoleItem> console(@RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "processType", required = false) String processType,
            @RequestParam(name = "olderThanHours", required = false) Integer olderThanHours) {
        return queries.console(status, processType, olderThanHours);
    }

    @GetMapping("/console/callbacks")
    @RequiresPermission(value = CorePermissions.WORKFLOW_ADMIN, tenantWide = true)
    public List<CallbackItem> openCallbacks() {
        return queries.openCallbacks();
    }

    @PostMapping("/console/callbacks/{callbackId}/retry")
    @RequiresPermission(value = CorePermissions.WORKFLOW_ADMIN, tenantWide = true)
    public ResponseEntity<Void> retryCallback(@PathVariable UUID callbackId) {
        engine.retryCallback(callbackId);
        return ResponseEntity.noContent().build();
    }
}
