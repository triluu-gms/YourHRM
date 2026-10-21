package vn.yourhrm;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/** Roles and permission matrix (US-CORE-034) + authentication / permission enforcement (US-CORE-039). */
class RoleApiIT extends IntegrationTest {

    static UUID tenant;
    static TestUser owner;
    static TestUser viewer;
    static TestUser nobody;
    static TestUser scopedRoleAdmin;
    static UUID systemRole;

    @BeforeAll
    static void data() {
        tenant = FIX.tenant("roles");
        owner = FIX.owner(tenant);
        systemRole = ADMIN.queryForObject("select id from core.role where tenant_id = ? and code = 'TENANT_OWNER'",
                UUID.class, tenant);
        UUID viewRole = FIX.role(tenant, "ROLE_VIEWER", false, "core.role.view", "core.permission.view");
        viewer = FIX.user(tenant, "viewer@test.local");
        FIX.assign(viewer, viewRole, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        nobody = FIX.user(tenant, "nobody@test.local");
        // holds core.role.view only for one legal entity: not enough for tenant administration endpoints
        UUID le = FIX.legalEntity(tenant, "LE-R");
        UUID scopedRole = FIX.role(tenant, "ROLE_VIEWER_LE", false, "core.role.view");
        scopedRoleAdmin = FIX.user(tenant, "scoped@test.local");
        FIX.assign(scopedRoleAdmin, scopedRole, "LEGAL_ENTITY", List.of(le), true, TestFixtures.EPOCH, null);
    }

    @Test
    void unauthenticatedRequestsGet401() throws Exception {
        mvc.perform(get("/api/v1/core/roles"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void missingPermissionGets403ProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/core/roles").with(as(nobody)))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value("forbidden"))
                .andExpect(jsonPath("$.status").value(403));
        // view permission does not allow writes
        mvc.perform(post("/api/v1/core/roles").with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X1\",\"nameVi\":\"x\",\"nameEn\":\"x\"}"))
                .andExpect(status().isForbidden());
        // a narrower-than-tenant scope is not enough for tenant administration
        mvc.perform(get("/api/v1/core/roles").with(as(scopedRoleAdmin))).andExpect(status().isForbidden());
    }

    @Test
    void viewerCanListAndReadRoles() throws Exception {
        mvc.perform(get("/api/v1/core/roles").with(as(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("TENANT_OWNER")));
        mvc.perform(get("/api/v1/core/roles/" + systemRole).with(as(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system").value(true))
                .andExpect(jsonPath("$.permissions[*].code", hasItem("core.role.view")));
        mvc.perform(get("/api/v1/core/roles/" + UUID.randomUUID()).with(as(viewer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void customRoleCrudFlow() throws Exception {
        String created = mvc.perform(post("/api/v1/core/roles").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"HR_CLERK","nameVi":"Nhân viên HR","nameEn":"HR clerk",
                                 "permissions":[{"code":"core.user.view"},{"code":"core.role.view","fieldMaskLevel":"PII"}]}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.permissions", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        int version = JsonPath.read(created, "$.version");

        // duplicate code -> 409
        mvc.perform(post("/api/v1/core/roles").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HR_CLERK\",\"nameVi\":\"x\",\"nameEn\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("role-code-taken"));

        // rename
        mvc.perform(put("/api/v1/core/roles/" + id).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameVi\":\"Chuyên viên HR\",\"nameEn\":\"HR specialist\",\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameEn").value("HR specialist"));
        // stale version -> 409
        mvc.perform(put("/api/v1/core/roles/" + id).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameVi\":\"x\",\"nameEn\":\"x\",\"version\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale-version"));

        // matrix replace
        mvc.perform(put("/api/v1/core/roles/" + id + "/permissions").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[{\"code\":\"core.user.view\"},{\"code\":\"core.user.create\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[*].code", containsInAnyOrder("core.user.view", "core.user.create")));

        // unknown permission -> 422
        mvc.perform(put("/api/v1/core/roles/" + id + "/permissions").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[{\"code\":\"core.nope.view\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown-permission"));

        // delete
        mvc.perform(delete("/api/v1/core/roles/" + id).with(as(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/core/roles/" + id).with(as(owner))).andExpect(status().isNotFound());
    }

    @Test
    void validationErrorsAreProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/core/roles").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad code!\",\"nameVi\":\"\",\"nameEn\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("code")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("nameVi")));
    }

    @Test
    void systemRoleIsImmutableButCopyable() throws Exception {
        mvc.perform(put("/api/v1/core/roles/" + systemRole).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameVi\":\"x\",\"nameEn\":\"x\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("system-role-immutable"));
        mvc.perform(put("/api/v1/core/roles/" + systemRole + "/permissions").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"permissions\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("system-role-immutable"));
        mvc.perform(delete("/api/v1/core/roles/" + systemRole).with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("system-role-immutable"));

        int ownerPermissions = FIX.allCorePermissions().size();
        mvc.perform(post("/api/v1/core/roles/" + systemRole + "/copy").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"OWNER_COPY\",\"nameVi\":\"Bản sao\",\"nameEn\":\"Owner copy\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.permissions", hasSize(ownerPermissions)));
    }

    @Test
    void roleInUseCannotBeDeleted() throws Exception {
        UUID role = FIX.role(tenant, "IN_USE", false, "core.user.view");
        FIX.assign(FIX.user(tenant, "holder-" + UUID.randomUUID() + "@test.local"), role, "TENANT", List.of(), true,
                TestFixtures.EPOCH, null);
        mvc.perform(delete("/api/v1/core/roles/" + role).with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("role-in-use"));
    }

    @Test
    void permissionCatalogueIsListed() throws Exception {
        mvc.perform(get("/api/v1/core/permissions").param("module", "core").with(as(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("core.role_assignment.create")))
                .andExpect(jsonPath("$[?(@.code=='core.role_assignment.create')].privileged").value(true))
                .andExpect(jsonPath("$[*].module", not(hasItem("pay"))));
        mvc.perform(get("/api/v1/core/permissions").with(as(nobody))).andExpect(status().isForbidden());
    }
}
