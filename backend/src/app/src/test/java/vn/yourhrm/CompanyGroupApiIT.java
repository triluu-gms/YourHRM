package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import vn.yourhrm.core.api.CompanyGroupProvisioning;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/** Company group profile (BR-CORE-001, US-CORE-001): idempotent provisioning, edit, audit, permissions. */
class CompanyGroupApiIT extends IntegrationTest {

    @Autowired
    CompanyGroupProvisioning provisioning;

    private boolean provision(UUID tenant) throws Exception {
        return TenantContext.callWith(new TenantContext(tenant, null, null, "provision", null), provisioning::provision);
    }

    @Test
    void provisioningCreatesTheProfileOnceFromTheTenantDefaults() throws Exception {
        UUID tenant = FIX.tenant("cg-prov");
        TestUser owner = FIX.owner(tenant);
        mvc.perform(get("/api/v1/core/company-group").with(as(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("company-group-not-provisioned"));

        assertThat(provision(tenant)).isTrue();
        assertThat(provision(tenant)).isFalse(); // event redelivery: no-op
        assertThat(ADMIN.queryForObject("select count(*) from core.company_group where tenant_id = ?", Integer.class,
                tenant)).isEqualTo(1);

        String tenantName = ADMIN.queryForObject("select name from saas.tenant where id = ?", String.class, tenant);
        mvc.perform(get("/api/v1/core/company-group").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(tenantName))
                .andExpect(jsonPath("$.defaultLocale").value("vi-VN"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.currencyCode").value("VND"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void ownerEditsTheProfileWithOptimisticLockingAndEveryChangeIsAudited() throws Exception {
        UUID tenant = FIX.tenant("cg-edit");
        TestUser owner = FIX.owner(tenant);
        provision(tenant);

        mvc.perform(put("/api/v1/core/company-group").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Tập đoàn ABC","defaultLocale":"en-US","timeZone":"Asia/Ho_Chi_Minh",
                                 "currencyCode":"VND","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tập đoàn ABC"))
                .andExpect(jsonPath("$.defaultLocale").value("en-US"))
                .andExpect(jsonPath("$.updatedBy").value(owner.userId().toString()))
                .andExpect(jsonPath("$.version").value(1));

        // a stale version is rejected
        mvc.perform(put("/api/v1/core/company-group").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","defaultLocale":"vi-VN","timeZone":"Asia/Ho_Chi_Minh",
                                 "currencyCode":"VND","version":0}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale-version"));

        Integer audited = ADMIN.queryForObject("""
                select count(*) from audit.audit_event
                 where tenant_id = ? and object_type = 'company_group' and action = 'UPDATE' and actor_id = ?""",
                Integer.class, tenant, owner.userId());
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void invalidDefaultsAndUnknownLogoAreRejected() throws Exception {
        UUID tenant = FIX.tenant("cg-bad");
        TestUser owner = FIX.owner(tenant);
        provision(tenant);
        String body = """
                {"name":"G","defaultLocale":"%s","timeZone":"%s","currencyCode":"%s","logoDocumentId":%s,"version":0}""";
        mvc.perform(put("/api/v1/core/company-group").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("vietnamese", "Asia/Ho_Chi_Minh", "VND", "null")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-locale"));
        mvc.perform(put("/api/v1/core/company-group").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("vi-VN", "Mars/Olympus", "VND", "null")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-time-zone"));
        mvc.perform(put("/api/v1/core/company-group").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("vi-VN", "Asia/Ho_Chi_Minh", "QQQ", "null")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-currency"));
        mvc.perform(put("/api/v1/core/company-group").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("vi-VN", "Asia/Ho_Chi_Minh", "VND", "\"" + UUID.randomUUID() + "\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-document"));
    }

    @Test
    void viewNeedsAnyScopeButEditNeedsATenantWideGrant() throws Exception {
        UUID tenant = FIX.tenant("cg-perm");
        provision(tenant);
        UUID le = FIX.legalEntity(tenant, "LE-CG");
        UUID role = FIX.role(tenant, "CG_LE_ADMIN", false, "core.company_group.view", "core.company_group.update");
        TestUser leAdmin = FIX.user(tenant, "le-admin@test.local");
        FIX.assign(leAdmin, role, "LEGAL_ENTITY", List.of(le), true, TestFixtures.EPOCH, null);
        TestUser nobody = FIX.user(tenant, "nobody@test.local");

        mvc.perform(get("/api/v1/core/company-group").with(as(leAdmin))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/core/company-group").with(as(nobody))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/core/company-group").with(as(leAdmin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"G","defaultLocale":"vi-VN","timeZone":"Asia/Ho_Chi_Minh",
                                 "currencyCode":"VND","version":0}"""))
                .andExpect(status().isForbidden());
    }
}
