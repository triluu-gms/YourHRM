package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Role assignments (US-CORE-035): validation against master data, grant/revoke, validity periods, cache
 * eviction (US-CORE-038) and audit.
 */
class RoleAssignmentApiIT extends IntegrationTest {

    static final String PERM = "core.legal_entity.view";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    static UUID tenant;
    static TestUser owner;
    static UUID leViewer;
    static UUID le;

    @BeforeAll
    static void data() {
        tenant = FIX.tenant("assign");
        owner = FIX.owner(tenant);
        leViewer = FIX.role(tenant, "LE_VIEWER", false, PERM);
        le = FIX.legalEntity(tenant, "LE-A");
    }

    @Test
    void grantValidatesScopeAgainstMasterData() throws Exception {
        TestUser u = FIX.user(tenant, "v-" + UUID.randomUUID() + "@test.local");
        String url = "/api/v1/core/users/" + u.userId() + "/role-assignments";
        grant(url, "{\"roleId\":\"%s\",\"scopeType\":\"LEGAL_ENTITY\",\"scopeIds\":[\"%s\"]}"
                .formatted(leViewer, UUID.randomUUID()), 422, "unknown-scope-ids");
        grant(url, "{\"roleId\":\"%s\",\"scopeType\":\"LEGAL_ENTITY\"}".formatted(leViewer), 422, "scope-ids-required");
        grant(url, "{\"roleId\":\"%s\",\"scopeType\":\"TENANT\",\"scopeIds\":[\"%s\"]}".formatted(leViewer, le), 422,
                "scope-ids-not-allowed");
        grant(url, "{\"roleId\":\"%s\",\"scopeType\":\"TENANT\",\"validFrom\":\"2026-05-01\",\"validTo\":\"2026-05-01\"}"
                .formatted(leViewer), 422, "invalid-period");
        grant(url, "{\"roleId\":\"%s\",\"scopeType\":\"REGION\"}".formatted(leViewer), 400, "http-400");
        grant("/api/v1/core/users/" + UUID.randomUUID() + "/role-assignments",
                "{\"roleId\":\"%s\",\"scopeType\":\"TENANT\"}".formatted(leViewer), 404, "not-found");
        // an org-unit id is not a legal-entity id
        grant(url, "{\"roleId\":\"%s\",\"scopeType\":\"ORG_UNIT\",\"scopeIds\":[\"%s\"]}".formatted(leViewer, le), 422,
                "unknown-scope-ids");
    }

    @Test
    void grantThenRevokeTakesEffectImmediately() throws Exception {
        TestUser u = FIX.user(tenant, "g-" + UUID.randomUUID() + "@test.local");
        String url = "/api/v1/core/users/" + u.userId() + "/role-assignments";
        checkLe(u, false);

        String created = mvc.perform(post(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"%s\",\"scopeType\":\"LEGAL_ENTITY\",\"scopeIds\":[\"%s\"],\"validFrom\":\"2026-01-01\"}"
                                .formatted(leViewer, le)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activeToday").value(true))
                .andExpect(jsonPath("$.roleCode").value("LE_VIEWER"))
                .andExpect(jsonPath("$.grantedBy").value(owner.userId().toString()))
                .andReturn().getResponse().getContentAsString();
        String assignmentId = JsonPath.read(created, "$.id");
        checkLe(u, true); // granted -> allowed (and now cached)

        // audit row for the grant, with the acting user
        List<Map<String, Object>> audit = ADMIN.queryForList("""
                select actor_id, action from audit.audit_event where tenant_id = ? and object_type = 'role_assignment'
                   and object_id = ?""", tenant, UUID.fromString(assignmentId));
        assertThat(audit).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("actor_id", owner.userId()).containsEntry("action", "INSERT");
        });

        mvc.perform(delete(url + "/" + assignmentId).with(as(owner))).andExpect(status().isNoContent());
        checkLe(u, false); // cache evicted after commit: denied at once, no TTL wait

        // history keeps the closed assignment (valid_to = today, exclusive)
        mvc.perform(get(url).param("includeHistory", "true").with(as(owner)))
                .andExpect(jsonPath("$[0].id").value(assignmentId))
                .andExpect(jsonPath("$[0].validTo").value(TODAY.toString()))
                .andExpect(jsonPath("$[0].activeToday").value(false));
        mvc.perform(get(url).with(as(owner))).andExpect(jsonPath("$[*].id", not(hasItem(assignmentId))));
    }

    @Test
    void revokingAFutureAssignmentDeletesIt() throws Exception {
        TestUser u = FIX.user(tenant, "f-" + UUID.randomUUID() + "@test.local");
        UUID id = FIX.assign(u, leViewer, "TENANT", List.of(), true, TODAY.plusDays(10), null);
        mvc.perform(delete("/api/v1/core/users/" + u.userId() + "/role-assignments/" + id).with(as(owner)))
                .andExpect(status().isNoContent());
        assertThat(ADMIN.queryForObject("select count(*) from core.role_assignment where id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void expiredAndFutureAssignmentsDoNotGrant() throws Exception {
        TestUser expired = FIX.user(tenant, "e-" + UUID.randomUUID() + "@test.local");
        FIX.assign(expired, leViewer, "TENANT", List.of(), true, TestFixtures.EPOCH, TODAY); // ended today (exclusive)
        checkLe(expired, false);

        TestUser future = FIX.user(tenant, "fu-" + UUID.randomUUID() + "@test.local");
        FIX.assign(future, leViewer, "TENANT", List.of(), true, TODAY.plusDays(1), null);
        checkLe(future, false);

        TestUser current = FIX.user(tenant, "c-" + UUID.randomUUID() + "@test.local");
        FIX.assign(current, leViewer, "TENANT", List.of(), true, TestFixtures.EPOCH, TODAY.plusDays(1));
        checkLe(current, true);
    }

    @Test
    void changingTheRoleMatrixEvictsEveryHolder() throws Exception {
        UUID role = FIX.role(tenant, "MATRIX_" + UUID.randomUUID().toString().substring(0, 8), false, PERM);
        TestUser holder = FIX.user(tenant, "m-" + UUID.randomUUID() + "@test.local");
        FIX.assign(holder, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        checkLe(holder, true);

        mvc.perform(put("/api/v1/core/roles/" + role + "/permissions").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"permissions\":[{\"code\":\"core.location.view\"}]}"))
                .andExpect(status().isOk());
        checkLe(holder, false);

        Integer audited = ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and object_type = 'role_permission'
                   and actor_id = ?""", Integer.class, tenant, owner.userId());
        assertThat(audited).isPositive();
    }

    @Test
    void userCannotSeeOrManageAssignmentsWithoutPermission() throws Exception {
        TestUser u = FIX.user(tenant, "n-" + UUID.randomUUID() + "@test.local");
        mvc.perform(get("/api/v1/core/users/" + u.userId() + "/role-assignments").with(as(u)))
                .andExpect(status().isForbidden());
    }

    private void grant(String url, String body, int status, String code) throws Exception {
        var result = mvc.perform(post(url).with(as(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status));
        if (code != null) {
            result.andExpect(jsonPath("$.code").value(code));
        }
    }

    private void checkLe(TestUser u, boolean allowed) throws Exception {
        mvc.perform(get("/api/v1/core/me/authorisation/check").with(as(u)).param("permission", PERM)
                        .param("targetType", "LEGAL_ENTITY").param("targetId", le.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(allowed));
    }
}
