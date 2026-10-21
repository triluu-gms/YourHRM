package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures.TestUser;

/** Users (US-CORE-030), current-user context (US-CORE-041), created_by/updated_by + DDL row audit. */
class UserApiIT extends IntegrationTest {

    static UUID tenant;
    static TestUser owner;

    @BeforeAll
    static void data() {
        tenant = FIX.tenant("users");
        owner = FIX.owner(tenant);
    }

    @Test
    void createSearchGetUpdateFlowStampsActorAndWritesAudit() throws Exception {
        UUID identity = UUID.randomUUID();
        String body = """
                {"login":"Nguyen.Van.A@acme.vn","email":"nguyen.van.a@acme.vn","mobile":"0901234567",
                 "locale":"vi-VN","identityId":"%s"}""".formatted(identity);
        String created = mvc.perform(post("/api/v1/core/users").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdBy").value(owner.userId().toString()))
                .andExpect(jsonPath("$.updatedBy").value(owner.userId().toString()))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        // the DDL row-audit trigger wrote an audit event with the acting user (app.user_id)
        List<Map<String, Object>> audit = ADMIN.queryForList("""
                select actor_type, actor_id, action, object_type, request_id from audit.audit_event
                 where tenant_id = ? and object_id = ?""", tenant, UUID.fromString(id));
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).containsEntry("actor_type", "USER")
                .containsEntry("actor_id", owner.userId())
                .containsEntry("action", "INSERT")
                .containsEntry("object_type", "user_account");
        assertThat((String) audit.getFirst().get("request_id")).isNotBlank();

        // login is unique case-insensitively
        mvc.perform(post("/api/v1/core/users").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"nguyen.van.a@ACME.vn\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("login-taken"));

        mvc.perform(get("/api/v1/core/users").param("q", "van.a").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));

        mvc.perform(put("/api/v1/core/users/" + id).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a.nguyen@acme.vn","locale":"en-US","identityId":"%s","version":0}"""
                                .formatted(identity)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a.nguyen@acme.vn"))
                .andExpect(jsonPath("$.locale").value("en-US"))
                .andExpect(jsonPath("$.version").value(1));

        Integer updates = ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and object_id = ? and action = 'UPDATE'
                   and change -> 'email' is not null""", Integer.class, tenant, UUID.fromString(id));
        assertThat(updates).isEqualTo(1);
    }

    @Test
    void deactivatedUserLosesAccessAndCanBeReactivated() throws Exception {
        TestUser user = FIX.user(tenant, "deact-" + UUID.randomUUID() + "@test.local");
        mvc.perform(get("/api/v1/core/me").with(as(user))).andExpect(status().isOk());

        mvc.perform(post("/api/v1/core/users/" + user.userId() + "/deactivate").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.accessRevokedAt").isNotEmpty());
        mvc.perform(get("/api/v1/core/me").with(as(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("user-inactive"));

        mvc.perform(post("/api/v1/core/users/" + user.userId() + "/activate").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/core/me").with(as(user))).andExpect(status().isOk());
    }

    @Test
    void cannotDeactivateYourself() throws Exception {
        mvc.perform(post("/api/v1/core/users/" + owner.userId() + "/deactivate").with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("self-deactivation"));
    }

    @Test
    void unknownIdentityGets403() throws Exception {
        TestUser stranger = new TestUser(tenant, UUID.randomUUID(), UUID.randomUUID(), "x");
        mvc.perform(get("/api/v1/core/me").with(as(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("unknown-user"));
    }

    @Test
    void tokenWithoutTenantClaimGets403() throws Exception {
        mvc.perform(get("/api/v1/core/me").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(owner.identityId().toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("no-tenant"));
    }

    @Test
    void meReturnsUserTenantRolesAndEffectivePermissions() throws Exception {
        mvc.perform(get("/api/v1/core/me").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(owner.userId().toString()))
                .andExpect(jsonPath("$.tenant.id").value(tenant.toString()))
                .andExpect(jsonPath("$.tenant.state").value("ACTIVE"))
                .andExpect(jsonPath("$.locale").value("vi-VN"))
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0].code").value("TENANT_OWNER"))
                .andExpect(jsonPath("$.roles[0].scopeType").value("TENANT"))
                .andExpect(jsonPath("$.permissions[*].code", hasItem("core.user.create")))
                .andExpect(jsonPath("$.permissions[0].scopes[0].scopeType").value("TENANT"));
    }

    @Test
    void userWithoutRolesHasEmptyContextAndNoPermissions() throws Exception {
        TestUser plain = FIX.user(tenant, "plain-" + UUID.randomUUID() + "@test.local");
        mvc.perform(get("/api/v1/core/me").with(as(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(0)))
                .andExpect(jsonPath("$.permissions", hasSize(0)));
        mvc.perform(get("/api/v1/core/users").with(as(plain))).andExpect(status().isForbidden());
    }
}
