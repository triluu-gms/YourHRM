package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.yourhrm.core.api.CompanyGroupProvisioning;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowCompleted;
import vn.yourhrm.shared.workflow.api.WorkflowEvents.WorkflowWithdrawn;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartResult;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.RecordedEvents;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestProcessTypes;

/**
 * The approval engine (BR-CORE-050..061 P1): process types and definitions, approver resolution with fallback,
 * approver and requester actions, bulk approval, reassignment, the inbox, the immutable trail and callbacks.
 */
class WorkflowApiIT extends IntegrationTest {

    @Autowired
    WorkflowService workflow;

    @Autowired
    CompanyGroupProvisioning provisioning;

    @BeforeAll
    static void processTypes() {
        TestProcessTypes.register(ADMIN);
    }

    /**
     * A company: root R (managed by the director), SALES under it (managed by the manager), an employee in SALES,
     * an HR officer of the legal entity, and the tenant owner.
     */
    record World(UUID tenant, UUID le, TestFixtures.OrgUnit root, TestFixtures.OrgUnit sales, TestUser owner,
            TestUser director, TestUser manager, TestUser employee, TestUser hr) {
    }

    private World world(String name) throws Exception {
        UUID tenant = FIX.tenant(name);
        TenantContext.callWith(new TenantContext(tenant, null, null, "t", null), provisioning::provision);
        TestUser owner = FIX.user(tenant, "owner@test.local");
        FIX.assign(owner, ADMIN.queryForObject("select id from core.role where tenant_id = ? and code = 'TENANT_OWNER'",
                UUID.class, tenant), "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        UUID le = FIX.legalEntity(tenant, "LE");
        TestUser director = FIX.user(tenant, "director@test.local");
        TestUser manager = FIX.user(tenant, "manager@test.local");
        TestUser employee = FIX.user(tenant, "employee@test.local");
        TestUser hr = FIX.user(tenant, "hr@test.local");
        TestFixtures.OrgUnit root = FIX.orgUnit(tenant, "R", null, le, null, TestFixtures.EPOCH, null);
        TestFixtures.OrgUnit sales = FIX.orgUnit(tenant, "SALES", root, le, null, TestFixtures.EPOCH, null);
        ADMIN.update("update core.org_unit_version set manager_user_id = ? where org_unit_id = ?", director.userId(),
                root.id());
        ADMIN.update("update core.org_unit_version set manager_user_id = ? where org_unit_id = ?", manager.userId(),
                sales.id());
        UUID hrRole = FIX.role(tenant, TestProcessTypes.HR_ROLE, false, "core.org_unit.view", "core.workflow.view");
        FIX.assign(hr, hrRole, "LEGAL_ENTITY", List.of(le), true, TestFixtures.EPOCH, null);
        return new World(tenant, le, root, sales, owner, director, manager, employee, hr);
    }

    private static <T> T as(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", "vi-VN"), call);
    }

    private StartResult submit(World w, TestUser requester, String processType, UUID subject) throws Exception {
        return as(requester, () -> workflow.start(new StartRequest(processType, "att.leave_request", subject, 3,
                "key-" + subject, null, null, w.le(), w.sales().id(), null, null,
                Map.of("days", 2, "from", "2026-12-01"), Map.of("leaveType", "ANNUAL"), Map.of())));
    }

    private ResultActions act(TestUser user, UUID taskId, String body) throws Exception {
        return mvc.perform(post("/api/v1/core/workflow/tasks/" + taskId + "/actions").with(as(user))
                .contentType(MediaType.APPLICATION_JSON).content(body).header("User-Agent", "JUnit"));
    }

    private ResultActions requesterAct(TestUser user, UUID instanceId, String body) throws Exception {
        return mvc.perform(post("/api/v1/core/workflow/instances/" + instanceId + "/actions").with(as(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** The caller's single pending task id. */
    private UUID pendingTask(TestUser user) throws Exception {
        String json = mvc.perform(get("/api/v1/core/workflow/tasks").with(as(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$[*].taskId");
        assertThat(ids).hasSize(1);
        return UUID.fromString(ids.getFirst());
    }

    private static String instanceStatus(UUID instanceId) {
        return ADMIN.queryForObject("select status from core.workflow_instance where id = ?", String.class, instanceId);
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void aRequestFlowsThroughTheVendorDefinitionAndCallsTheOwnerBack() throws Exception {
        World w = world("wf-happy");
        UUID subject = UUID.randomUUID();
        StartResult started = submit(w, w.employee(), TestProcessTypes.LEAVE, subject);
        assertThat(started.created()).isTrue();
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        // a retried submit (same idempotency key) returns the same instance
        assertThat(submit(w, w.employee(), TestProcessTypes.LEAVE, subject)).isEqualTo(
                new StartResult(started.instanceId(), "IN_PROGRESS", false));

        // step 1: the line manager's inbox
        mvc.perform(get("/api/v1/core/workflow/tasks/counts").with(as(w.manager())))
                .andExpect(jsonPath("$['att.TEST_LEAVE']").value(1));
        mvc.perform(get("/api/v1/core/workflow/tasks").with(as(w.manager())))
                .andExpect(jsonPath("$[0].stepKey").value("manager"))
                .andExpect(jsonPath("$[0].requesterName").value("employee@test.local"))
                .andExpect(jsonPath("$[0].summary.days").value(2));
        UUID managerTask = pendingTask(w.manager());
        mvc.perform(get("/api/v1/core/workflow/instances/" + started.instanceId()).with(as(w.manager())))
                .andExpect(jsonPath("$.allowedActions", contains("APPROVE", "REJECT", "RETURN", "REQUEST_INFO",
                        "FORWARD", "COMMENT")))
                .andExpect(jsonPath("$.approverChain[1].approvers[0]").value(w.hr().userId().toString()));
        act(w.manager(), managerTask, "{\"action\":\"APPROVE\",\"comment\":\"OK\"}").andExpect(status().isNoContent());
        act(w.manager(), managerTask, "{\"action\":\"APPROVE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("task-not-pending"));

        // step 2: the HR of the legal entity (role in the request's scope)
        UUID hrTask = pendingTask(w.hr());
        act(w.employee(), hrTask, "{\"action\":\"APPROVE\"}").andExpect(status().isNotFound()); // not the assignee
        act(w.hr(), hrTask, "{\"action\":\"APPROVE\"}").andExpect(status().isNoContent());
        assertThat(instanceStatus(started.instanceId())).isEqualTo("APPROVED");

        // the owner module is told, with the history, and acknowledges (idempotently)
        WorkflowCompleted done = RecordedEvents.of(WorkflowCompleted.class).stream()
                .filter(e -> e.instanceId().equals(started.instanceId())).findFirst().orElseThrow();
        assertThat(done.outcome()).isEqualTo("APPROVED");
        assertThat(done.subjectVersion()).isEqualTo(3);
        assertThat(done.history()).extracting(h -> h.action()).containsExactly("SUBMIT", "APPROVE", "APPROVE", "COMPLETE");
        as(w.employee(), () -> {
            workflow.acknowledgeCallback(done.callbackId(), Map.of("leaveStatus", "APPROVED"));
            workflow.acknowledgeCallback(done.callbackId(), Map.of("leaveStatus", "APPROVED"));
            return null;
        });
        assertThat(ADMIN.queryForObject("select status from core.workflow_callback where id = ?", String.class,
                done.callbackId())).isEqualTo("ACKED");

        // my requests and the printable trail (who, when, action, comment, channel, IP, device)
        mvc.perform(get("/api/v1/core/workflow/requests").with(as(w.employee())))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
        mvc.perform(get("/api/v1/core/workflow/instances/" + started.instanceId() + "/trail").with(as(w.employee())))
                .andExpect(jsonPath("$[*].action", contains("SUBMIT", "APPROVE", "APPROVE", "COMPLETE")))
                .andExpect(jsonPath("$[1].actorName").value("manager@test.local"))
                .andExpect(jsonPath("$[1].comment").value("OK"))
                .andExpect(jsonPath("$[1].channel").value("WEB"))
                .andExpect(jsonPath("$[1].userAgent").value("JUnit"))
                .andExpect(jsonPath("$[1].clientIp").value("127.0.0.1"));
        // the trail is immutable, even for the database owner
        assertThatThrownBy(() -> ADMIN.update("update core.workflow_action_log set comment = 'x' where workflow_instance_id = ?",
                started.instanceId())).hasMessageContaining("append-only");
        // an outsider does not see the request
        TestUser stranger = FIX.user(w.tenant(), "stranger@test.local");
        mvc.perform(get("/api/v1/core/workflow/instances/" + started.instanceId()).with(as(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectReturnInformationAndWithdraw() throws Exception {
        World w = world("wf-actions");
        StartResult first = submit(w, w.employee(), TestProcessTypes.LEAVE, UUID.randomUUID());
        UUID task = pendingTask(w.manager());
        act(w.manager(), task, "{\"action\":\"REJECT\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("comment-required"));
        act(w.manager(), task, "{\"action\":\"REJECT\",\"comment\":\"Trùng lịch dự án\"}")
                .andExpect(status().isNoContent());
        assertThat(instanceStatus(first.instanceId())).isEqualTo("REJECTED");

        StartResult second = submit(w, w.employee(), TestProcessTypes.LEAVE, UUID.randomUUID());
        // return to the requester, who corrects and resubmits
        act(w.manager(), pendingTask(w.manager()), "{\"action\":\"RETURN\",\"comment\":\"Thiếu ngày bắt đầu\"}")
                .andExpect(status().isNoContent());
        assertThat(instanceStatus(second.instanceId())).isEqualTo("RETURNED");
        mvc.perform(get("/api/v1/core/workflow/instances/" + second.instanceId()).with(as(w.employee())))
                .andExpect(jsonPath("$.allowedActions", contains("WITHDRAW", "RESUBMIT", "COMMENT")));
        requesterAct(w.employee(), second.instanceId(), "{\"action\":\"RESUBMIT\",\"comment\":\"Đã bổ sung\"}")
                .andExpect(status().isNoContent());
        // request information; the approver cannot decide until it is provided
        UUID again = pendingTask(w.manager());
        act(w.manager(), again, "{\"action\":\"REQUEST_INFO\",\"comment\":\"Ai thay thế?\"}")
                .andExpect(status().isNoContent());
        act(w.manager(), again, "{\"action\":\"APPROVE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("instance-not-in-progress"));
        requesterAct(w.employee(), second.instanceId(), "{\"action\":\"PROVIDE_INFO\",\"comment\":\"Anh B\"}")
                .andExpect(status().isNoContent());
        // the manager passes the step on to the HR officer (forward)
        act(w.manager(), again, "{\"action\":\"FORWARD\",\"forwardTo\":\"" + w.hr().userId() + "\"}")
                .andExpect(status().isNoContent());
        assertThat(pendingTask(w.hr())).isEqualTo(again);
        // the requester withdraws before the final decision; only the requester may
        requesterAct(w.manager(), second.instanceId(), "{\"action\":\"WITHDRAW\"}").andExpect(status().isNotFound());
        requesterAct(w.employee(), second.instanceId(), "{\"action\":\"WITHDRAW\"}").andExpect(status().isNoContent());
        assertThat(instanceStatus(second.instanceId())).isEqualTo("WITHDRAWN");
        assertThat(RecordedEvents.of(WorkflowWithdrawn.class)).extracting(WorkflowWithdrawn::instanceId)
                .contains(second.instanceId());
        assertThat(ADMIN.queryForObject("select outcome from core.workflow_callback where workflow_instance_id = ?",
                String.class, second.instanceId())).isEqualTo("WITHDRAWN");
        mvc.perform(get("/api/v1/core/workflow/instances/" + second.instanceId() + "/trail").with(as(w.employee())))
                .andExpect(jsonPath("$[*].action", contains("SUBMIT", "RETURN", "SUBMIT", "REQUEST_INFO",
                        "PROVIDE_INFO", "FORWARD", "WITHDRAW", "COMPLETE")));
    }

    @Test
    void approverFallbackAndDuplicateApproverSkipping() throws Exception {
        World w = world("wf-fallback");
        // the manager asks for leave: their own line manager is the director (they are skipped in the chain)
        StartResult own = submit(w, w.manager(), TestProcessTypes.OVERTIME, UUID.randomUUID());
        assertThat(pendingTask(w.director())).isNotNull();
        assertThat(ADMIN.queryForObject("""
                select approver_chain_snapshot -> 0 ->> 'approvers' from core.workflow_instance where id = ?""",
                String.class, own.instanceId())).contains(w.director().userId().toString());

        // a definition whose second step resolves to the same person is shortened (duplicate approver skipped)
        mvc.perform(post("/api/v1/core/workflow-definitions").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"processTypeCode":"att.TEST_OT","code":"OT-TWO-STEPS","nameVi":"Hai bước","validFrom":"2020-01-01",
                                 "steps":[{"stepKey":"lm","nameVi":"QL","resolver":{"type":"LINE_MANAGER"},"allowReturn":true},
                                          {"stepKey":"lm1","nameVi":"QL 1 cấp","resolver":{"type":"MANAGER_N_UP","params":{"levels":"1"}},
                                           "allowReturn":false}],
                                 "onResolverFailure":"LE_HR_ADMIN"}"""))
                .andExpect(status().isCreated());
        StartResult dup = submit(w, w.employee(), TestProcessTypes.OVERTIME, UUID.randomUUID());
        assertThat(ADMIN.queryForObject("""
                select approver_chain_snapshot -> 1 ->> 'via' from core.workflow_instance where id = ?""",
                String.class, dup.instanceId())).isEqualTo("SKIPPED");

        // nobody usable above (director deactivated): fall back to the legal entity's HR Admin holders
        ADMIN.update("update core.user_account set status = 'DEACTIVATED' where id = ?", w.director().userId());
        TestUser hrAdmin = FIX.user(w.tenant(), "hradmin@test.local");
        FIX.assign(hrAdmin, ADMIN.queryForObject("select id from core.role where tenant_id = ? and code = 'HR_ADMIN'",
                UUID.class, w.tenant()), "LEGAL_ENTITY", List.of(w.le()), true, TestFixtures.EPOCH, null);
        StartResult fallback = submit(w, w.manager(), TestProcessTypes.OVERTIME, UUID.randomUUID());
        assertThat(ADMIN.queryForMap("""
                select approver_chain_snapshot -> 0 ->> 'via' as via, approver_chain_snapshot -> 0 ->> 'reason' as reason
                  from core.workflow_instance where id = ?""", fallback.instanceId()))
                .containsEntry("via", "FALLBACK").extractingByKey("reason").asString().contains("LE_HR_ADMIN");
        assertThat(ADMIN.queryForObject("""
                select assignee_user_id from core.workflow_task where workflow_instance_id = ? and status = 'PENDING'""",
                UUID.class, fallback.instanceId())).isEqualTo(hrAdmin.userId());

        // with FAIL and nobody usable, the submit itself is refused (the owner's transaction rolls back)
        mvc.perform(post("/api/v1/core/workflow-definitions").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"processTypeCode":"att.TEST_OT","code":"OT-TWO-STEPS","nameVi":"Hai bước","validFrom":"2025-01-01",
                                 "steps":[{"stepKey":"lm","nameVi":"QL","resolver":{"type":"MANAGER_N_UP","params":{"levels":"5"}},
                                           "allowReturn":true}],
                                 "onResolverFailure":"FAIL"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions[*].versionNo", contains(1, 2)))
                .andExpect(jsonPath("$.versions[0].validTo").value("2025-01-01"));
        assertThatThrownBy(() -> submit(w, w.employee(), TestProcessTypes.OVERTIME, UUID.randomUUID()))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code())
                .isEqualTo("approver-unresolved");
    }

    @Test
    void definitionsMatchMostSpecificFirstAndAreValidated() throws Exception {
        World w = world("wf-defs");
        UUID otherLe = FIX.legalEntity(w.tenant(), "LE2");
        String def = """
                {"processTypeCode":"att.TEST_LEAVE","code":"%s","nameVi":"%s","validFrom":"2020-01-01","matchPriority":%d,
                 "conditions":%s,
                 "steps":[{"stepKey":"named","nameVi":"Duyệt","resolver":{"type":"USER","params":{"userId":"%s"}},"allowReturn":false}]}""";
        mvc.perform(post("/api/v1/core/workflow-definitions").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                .content(def.formatted("LEAVE-ALL", "Mọi pháp nhân", 1, "{}", w.director().userId())))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/core/workflow-definitions").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                .content(def.formatted("LEAVE-LE", "Pháp nhân LE, nghỉ năm", 50,
                        "{\"legalEntityIds\":[\"" + w.le() + "\"],\"fields\":{\"leaveType\":[\"ANNUAL\"]}}",
                        w.hr().userId())))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/core/workflow-definitions").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                .content(def.formatted("LEAVE-LE2", "Pháp nhân khác", 1,
                        "{\"legalEntityIds\":[\"" + otherLe + "\"]}", w.manager().userId())))
                .andExpect(status().isCreated());

        // the most specific matching definition wins (LE + field beats "all", whatever the priority)
        submit(w, w.employee(), TestProcessTypes.LEAVE, UUID.randomUUID());
        assertThat(pendingTask(w.hr())).isNotNull();

        mvc.perform(post("/api/v1/core/workflow-definitions").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content(def.formatted("BAD", "x", 1, "{\"fields\":{\"salary\":[\"1\"]}}", w.hr().userId())))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-condition-field"));
        mvc.perform(post("/api/v1/core/workflow-definitions").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content(def.formatted("BAD", "x", 1, "{}", w.hr().userId()).replace("att.TEST_LEAVE", "att.NOPE")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-process-type"));
        mvc.perform(get("/api/v1/core/process-types").with(as(w.owner())))
                .andExpect(jsonPath("$[*].code", hasItem("att.TEST_LEAVE")))
                .andExpect(jsonPath("$[?(@.code == 'att.TEST_LEAVE')].hasVendorDefault").value(true));
        mvc.perform(get("/api/v1/core/workflow-definitions?processType=att.TEST_LEAVE").with(as(w.owner())))
                .andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/v1/core/workflow-definitions").with(as(w.employee()))).andExpect(status().isForbidden());
    }

    @Test
    void bulkApproveReassignAndTheConsole() throws Exception {
        World w = world("wf-admin");
        StartResult a = submit(w, w.employee(), TestProcessTypes.LEAVE, UUID.randomUUID());
        StartResult b = submit(w, w.employee(), TestProcessTypes.LEAVE, UUID.randomUUID());
        StartResult ot = submit(w, w.employee(), TestProcessTypes.OVERTIME, UUID.randomUUID());
        String json = mvc.perform(get("/api/v1/core/workflow/tasks").with(as(w.manager()))).andReturn().getResponse()
                .getContentAsString();
        List<String> tasks = JsonPath.read(json, "$[*].taskId");
        List<String> otTask = JsonPath.read(json, "$[?(@.processTypeCode == 'att.TEST_OT')].taskId");

        // bulk approve: each task on its own; the overtime type does not allow it
        mvc.perform(post("/api/v1/core/workflow/tasks/bulk-approve").with(as(w.manager()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskIds\":[\"" + String.join("\",\"", tasks) + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.approved == true)]", hasSize(2)))
                .andExpect(jsonPath("$[?(@.taskId == '" + otTask.getFirst() + "')].error")
                        .value(hasItem(startsWith("bulk-not-allowed"))));
        assertThat(ADMIN.queryForObject("select count(*) from core.workflow_task where workflow_instance_id in (?, ?) "
                + "and step_key = 'hr' and status = 'PENDING'", Integer.class, a.instanceId(), b.instanceId())).isEqualTo(2);

        // reassignment needs a reason and an assignee who sees the request's organisation
        UUID otTaskId = UUID.fromString(otTask.getFirst());
        TestUser outsider = FIX.user(w.tenant(), "outsider@test.local");
        mvc.perform(post("/api/v1/core/workflow/tasks/" + otTaskId + "/reassign").with(as(w.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"" + outsider.userId() + "\",\"reason\":\"Nghỉ ốm\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("assignee-out-of-scope"));
        mvc.perform(post("/api/v1/core/workflow/tasks/" + otTaskId + "/reassign").with(as(w.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"" + w.hr().userId() + "\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/core/workflow/reassignments").with(as(w.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromUserId\":\"" + w.manager().userId() + "\",\"toUserId\":\"" + w.director().userId()
                                + "\",\"reason\":\"Quản lý nghỉ ốm\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", contains(otTaskId.toString())));
        assertThat(pendingTask(w.director())).isEqualTo(otTaskId);
        mvc.perform(post("/api/v1/core/workflow/tasks/" + otTaskId + "/reassign").with(as(w.employee()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"" + w.hr().userId() + "\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());

        // console: cancel with a reason; unacknowledged callbacks can be retried after a reported failure
        mvc.perform(post("/api/v1/core/workflow/instances/" + ot.instanceId() + "/cancel").with(as(w.owner()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Nhập trùng\"}"))
                .andExpect(status().isNoContent());
        UUID callback = ADMIN.queryForObject("select id from core.workflow_callback where workflow_instance_id = ?",
                UUID.class, ot.instanceId());
        as(w.employee(), () -> {
            workflow.reportCallbackFailure(callback, "leave request locked");
            return null;
        });
        mvc.perform(get("/api/v1/core/workflow/console/callbacks").with(as(w.owner())))
                .andExpect(jsonPath("$[?(@.callbackId == '" + callback + "')].status").value("FAILED"))
                .andExpect(jsonPath("$[?(@.callbackId == '" + callback + "')].lastError").value("leave request locked"));
        mvc.perform(post("/api/v1/core/workflow/console/callbacks/" + callback + "/retry").with(as(w.owner())))
                .andExpect(status().isNoContent());
        assertThat(RecordedEvents.of(WorkflowCompleted.class)).filteredOn(e -> e.callbackId().equals(callback))
                .hasSize(2).allMatch(e -> e.outcome().equals("CANCELLED"));
        mvc.perform(get("/api/v1/core/workflow/console/instances?status=IN_PROGRESS").with(as(w.owner())))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/v1/core/workflow/console/instances").with(as(w.hr()))).andExpect(status().isForbidden());

        // HR with core.workflow.view sees requests in their legal entity
        mvc.perform(get("/api/v1/core/workflow/instances/" + ot.instanceId()).with(as(w.hr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
