package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.CompanyGroupProvisioning;
import vn.yourhrm.core.api.CoreEvents.UserStatusChanged;
import vn.yourhrm.core.api.UserLifecycle;
import vn.yourhrm.core.internal.application.AuthorisationCache;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.MaskLevel;
import vn.yourhrm.shared.auth.api.SensitiveDataAccess;
import vn.yourhrm.shared.auth.api.spi.SupportGrants.Grant;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.RecordedEvents;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestSupportGrants;

/**
 * Users, roles and access-control gaps closed in increment 2: display name and expiry (BR-CORE-030), system roles
 * (BR-CORE-031), derived LINE_MANAGER / SELF grants (BR-CORE-033), masking policy and sensitive-read audit
 * (BR-CORE-034), the recipient filter (BR-CORE-035) and platform support sessions (BR-CORE-041).
 */
class AccessControlIT extends IntegrationTest {

    @Autowired
    CompanyGroupProvisioning provisioning;

    @Autowired
    UserLifecycle userLifecycle;

    @Autowired
    AuthorizationService authz;

    @Autowired
    SensitiveDataAccess sensitive;

    @Autowired
    AuthorisationCache authCache;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    JdbcClient appJdbc;

    @Autowired
    BusinessClock clock;

    private static <T> T asUser(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", "vi-VN"), call);
    }

    private static <T> T inTenant(UUID tenant, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(tenant, null, null, "t", null), call);
    }

    private UUID provisionedTenant(String name) throws Exception {
        UUID tenant = FIX.tenant(name);
        inTenant(tenant, provisioning::provision);
        return tenant;
    }

    /** The provisioned system Tenant Owner role held by a new user on the whole tenant. */
    private static TestUser owner(UUID tenant) {
        TestUser owner = FIX.user(tenant, "owner-" + tenant.toString().substring(24) + "@test.local");
        FIX.assign(owner, systemRole(tenant, "TENANT_OWNER"), "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        return owner;
    }

    private static UUID systemRole(UUID tenant, String code) {
        return ADMIN.queryForObject("select id from core.role where tenant_id = ? and code = ? and is_system",
                UUID.class, tenant, code);
    }

    private static UUID unitType(UUID tenant) {
        return ADMIN.queryForObject("select id from core.org_unit_type where tenant_id = ? and code = 'UNIT'",
                UUID.class, tenant);
    }

    private static String createdId(ResultActions created) throws Exception {
        return JsonPath.read(created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                "$.id");
    }

    // ---------------------------------------------------------------------------------------------- BR-CORE-031

    @Test
    void systemRolesAreSeededFromTemplatesAndResynced() throws Exception {
        UUID tenant = provisionedTenant("ac-sys");
        assertThat(ADMIN.queryForList("select code from core.role where tenant_id = ? and is_system order by code",
                String.class, tenant))
                .containsExactly("AUDITOR", "ESS", "FORMER_EMPLOYEE", "HR_ADMIN", "LINE_MANAGER", "TENANT_OWNER");
        UUID auditor = systemRole(tenant, "AUDITOR");
        assertThat(ADMIN.queryForList("""
                select p.action from core.role_permission rp join core.permission p using (permission_code)
                 where rp.tenant_id = ? and rp.role_id = ?""", String.class, tenant, auditor))
                .isNotEmpty().containsOnly("VIEW");
        assertThat(ADMIN.queryForList("select distinct field_mask_level from core.role_permission where role_id = ?",
                String.class, auditor)).containsExactly("PII_AND_PAY");
        assertThat(ADMIN.queryForList("select permission_code from core.role_permission where role_id = ?",
                String.class, systemRole(tenant, "HR_ADMIN")))
                .contains("core.org_unit.update", "core.user.create").doesNotContain("core.role.update");

        // a tampered or outdated matrix is brought back in line by the next sync (release job / provisioning)
        ADMIN.update("delete from core.role_permission where role_id = ? and permission_code = 'core.org_unit.view'",
                auditor);
        inTenant(tenant, provisioning::provision);
        assertThat(ADMIN.queryForObject("""
                select count(*) from core.role_permission where role_id = ? and permission_code = 'core.org_unit.view'""",
                Integer.class, auditor)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------- BR-CORE-033

    @Test
    void lineManagerScopeIsDerivedFromOrgUnitManagementAndCannotBeGranted() throws Exception {
        UUID tenant = provisionedTenant("ac-lm");
        TestUser owner = owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        TestUser manager = FIX.user(tenant, "manager@test.local");
        TestUser successor = FIX.user(tenant, "successor@test.local");
        String unit = """
                {"code":"%s","validFrom":"2020-01-01","attributes":{"nameVi":"%s","typeId":"%s","parentId":%s,
                 "legalEntityId":"%s","managerUserId":%s}}""";
        String root = createdId(mvc.perform(post("/api/v1/core/org-units").with(as(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(unit.formatted("R", "Công ty", unitType(tenant), "null", le, "null"))));
        String sales = createdId(mvc.perform(post("/api/v1/core/org-units").with(as(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(unit.formatted("SALES", "Kinh doanh", unitType(tenant), "\"" + root + "\"", le,
                        "\"" + manager.userId() + "\""))));
        createdId(mvc.perform(post("/api/v1/core/org-units").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(unit.formatted("SALES-N", "Kinh doanh miền Bắc", unitType(tenant), "\"" + sales + "\"", le,
                        "null"))));

        // no assignment at all: the managed subtree comes from org data
        mvc.perform(get("/api/v1/core/org-units").with(as(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("SALES", "SALES-N")));
        mvc.perform(get("/api/v1/core/me").with(as(manager)))
                .andExpect(jsonPath("$.roles[*].code", contains("LINE_MANAGER")))
                .andExpect(jsonPath("$.roles[0].assignmentId").doesNotExist());

        // management moves to the successor from today: access follows without any admin action
        mvc.perform(post("/api/v1/core/org-units/" + sales + "/versions").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + clock.today() + "\",\"attributes\":{\"nameVi\":\"Kinh doanh\","
                                + "\"typeId\":\"" + unitType(tenant) + "\",\"parentId\":\"" + root + "\",\"legalEntityId\":\""
                                + le + "\",\"managerUserId\":\"" + successor.userId() + "\"},\"version\":"
                                + ADMIN.queryForObject("select version from core.org_unit where id = ?::uuid", Integer.class, sales)
                                + "}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/core/org-units").with(as(manager))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/core/org-units").with(as(successor)))
                .andExpect(jsonPath("$[*].code", contains("SALES", "SALES-N")));

        // derived roles are never assigned by hand
        mvc.perform(post("/api/v1/core/users/" + manager.userId() + "/role-assignments").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"" + systemRole(tenant, "LINE_MANAGER") + "\",\"scopeType\":\"TENANT\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("derived-role"));
    }

    @Test
    void selfScopeFollowsTheEmploymentLinkAndStatus() throws Exception {
        UUID tenant = provisionedTenant("ac-self");
        TestUser employee = FIX.user(tenant, "emp@test.local");
        UUID employment = UUID.randomUUID();
        ADMIN.update("update core.user_account set employment_id = ? where id = ?", employment, employee.userId());
        mvc.perform(get("/api/v1/core/me").with(as(employee)))
                .andExpect(jsonPath("$.roles[*].code", contains("ESS")))
                .andExpect(jsonPath("$.roles[0].scopeType").value("SELF"));
        assertThat(asUser(employee, () -> authz.can("core.org_unit.view",
                new AuthorizationTarget.Employee(employment, null, null, null)))).isTrue();
        assertThat(asUser(employee, () -> authz.can("core.org_unit.view",
                new AuthorizationTarget.Employee(UUID.randomUUID(), null, null, null)))).isFalse();

        // after termination the same user keeps only the former-employee self-service
        ADMIN.update("update core.user_account set status = 'FORMER_EMPLOYEE' where id = ?", employee.userId());
        authCache.evictUser(tenant, employee.userId()); // the People-driven status change publishes UserStatusChanged
        mvc.perform(get("/api/v1/core/me").with(as(employee)))
                .andExpect(jsonPath("$.roles[*].code", contains("FORMER_EMPLOYEE")));
    }

    // ---------------------------------------------------------------------------------------------- BR-CORE-034

    @Test
    void maskLevelIsTheLeastRestrictiveGrantAndSensitiveReadsAreAudited() throws Exception {
        UUID tenant = FIX.tenant("ac-mask");
        UUID le = FIX.legalEntity(tenant, "LE");
        TestUser hr = FIX.user(tenant, "hr@test.local");
        UUID masked = FIX.role(tenant, "R_MASKED", false, "core.legal_entity.view");
        ADMIN.update("update core.role_permission set field_mask_level = 'PII_AND_PAY' where role_id = ?", masked);
        UUID partial = FIX.role(tenant, "R_PARTIAL", false, "core.legal_entity.view");
        ADMIN.update("update core.role_permission set field_mask_level = 'PII' where role_id = ?", partial);
        FIX.assign(hr, masked, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        FIX.assign(hr, partial, "LEGAL_ENTITY", List.of(le), true, TestFixtures.EPOCH, null);
        TestUser nobody = FIX.user(tenant, "nobody@test.local");

        assertThat(asUser(hr, () -> sensitive.maskLevelFor("core.legal_entity.view"))).isEqualTo(MaskLevel.PII);
        assertThat(asUser(nobody, () -> sensitive.maskLevelFor("core.legal_entity.view")))
                .isEqualTo(MaskLevel.PII_AND_PAY);

        // the level reaches the database (app.mask_level) for the masking views read in the same transaction
        String applied = asUser(hr, () -> new TransactionTemplate(txManager).execute(s -> {
            sensitive.applyMaskLevel("core.legal_entity.view");
            return appJdbc.sql("select current_setting('app.mask_level', true)").query(String.class).single();
        }));
        assertThat(applied).isEqualTo("PII");

        UUID subject = UUID.randomUUID();
        asUser(hr, () -> {
            sensitive.recordSensitiveRead("core.legal_entity.view", "hr.salary_record", subject, le);
            return null;
        });
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and action = 'READ_SENSITIVE'
                   and is_sensitive_read and object_id = ? and actor_id = ? and change ->> 'maskLevel' = 'PII'""",
                Integer.class, tenant, subject, hr.userId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------- BR-CORE-035

    @Test
    void recipientFilterReturnsTheUsersWhoseScopeCoversTheTarget() throws Exception {
        UUID tenant = provisionedTenant("ac-rcpt");
        TestUser owner = owner(tenant);
        UUID le1 = FIX.legalEntity(tenant, "LE1");
        UUID le2 = FIX.legalEntity(tenant, "LE2");
        UUID hrRole = FIX.role(tenant, "HR_OU", false, "core.org_unit.view");
        TestUser hr1 = FIX.user(tenant, "hr1@test.local");
        FIX.assign(hr1, hrRole, "LEGAL_ENTITY", List.of(le1), true, TestFixtures.EPOCH, null);
        TestUser hr2 = FIX.user(tenant, "hr2@test.local");
        FIX.assign(hr2, hrRole, "LEGAL_ENTITY", List.of(le2), true, TestFixtures.EPOCH, null);
        TestUser gone = FIX.user(tenant, "gone@test.local");
        FIX.assign(gone, hrRole, "LEGAL_ENTITY", List.of(le1), true, TestFixtures.EPOCH, null);
        ADMIN.update("update core.user_account set status = 'DEACTIVATED' where id = ?", gone.userId());
        TestUser manager = FIX.user(tenant, "mgr@test.local");
        TestFixtures.OrgUnit unit = FIX.orgUnit(tenant, "U1", null, le1, null, TestFixtures.EPOCH, null);
        ADMIN.update("update core.org_unit_version set manager_user_id = ? where org_unit_id = ?", manager.userId(),
                unit.id());

        Set<UUID> recipients = asUser(owner, () -> authz.usersWhoCan("core.org_unit.view",
                new AuthorizationTarget.OrgUnit(unit.id()), clock.today()));
        assertThat(recipients).contains(owner.userId(), hr1.userId(), manager.userId())
                .doesNotContain(hr2.userId(), gone.userId());
    }

    // ---------------------------------------------------------------------------------------------- BR-CORE-030

    @Test
    void displayNameAndExpiredUsersAreDeactivated() throws Exception {
        UUID tenant = FIX.tenant("ac-exp");
        TestUser owner = FIX.owner(tenant);
        mvc.perform(post("/api/v1/core/users").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"auditor@ext.vn\",\"displayName\":\"Kiểm toán độc lập\","
                                + "\"expiresAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Kiểm toán độc lập"));

        assertThat(inTenant(tenant, userLifecycle::deactivateExpiredUsers)).isEqualTo(1);
        assertThat(inTenant(tenant, userLifecycle::deactivateExpiredUsers)).isZero(); // idempotent
        assertThat(ADMIN.queryForObject(
                "select status from core.user_account where tenant_id = ? and login = 'auditor@ext.vn'",
                String.class, tenant)).isEqualTo("DEACTIVATED");
        assertThat(RecordedEvents.of(UserStatusChanged.class)).filteredOn(e -> e.tenantId().equals(tenant))
                .extracting(UserStatusChanged::status).contains("DEACTIVATED");
    }

    // ---------------------------------------------------------------------------------------------- BR-CORE-041

    private static RequestPostProcessor operator(UUID tenant, UUID operatorId, UUID grantId) {
        return jwt().jwt(j -> j.subject(operatorId.toString()).claim("tenant_id", tenant.toString())
                .claim("operator_id", operatorId.toString()).claim("support_grant_id", grantId.toString()));
    }

    private static Grant grant(UUID tenant, UUID operatorId, boolean readWrite, String status, Instant from,
            Instant to, UUID approvedBy) {
        return new Grant(UUID.randomUUID(), tenant, operatorId, "TICKET-1", Set.of("core"), readWrite, false, status,
                approvedBy, from, to);
    }

    @Test
    void supportAccessNeedsAnApprovedGrantAndEverythingIsInTheTenantAudit() throws Exception {
        UUID tenant = FIX.tenant("ac-support");
        TestUser owner = FIX.owner(tenant);
        FIX.legalEntity(tenant, "LE");
        UUID operatorId = UUID.randomUUID();
        Instant now = Instant.now();

        // unknown grant / not approved / expired / another operator's grant: refused and logged
        mvc.perform(get("/api/v1/core/legal-entities").with(operator(tenant, operatorId, UUID.randomUUID())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("support-grant-invalid"));
        Grant requested = grant(tenant, operatorId, false, "REQUESTED", now.minusSeconds(60), now.plusSeconds(3600),
                null);
        Grant expired = grant(tenant, operatorId, false, "APPROVED", now.minus(2, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.DAYS), owner.userId());
        Grant foreign = grant(tenant, UUID.randomUUID(), false, "APPROVED", now.minusSeconds(60),
                now.plusSeconds(3600), owner.userId());
        for (Grant g : List.of(requested, expired, foreign)) {
            TestSupportGrants.put(g);
            mvc.perform(get("/api/v1/core/legal-entities").with(operator(tenant, operatorId, g.id())))
                    .andExpect(status().isForbidden());
        }

        // approved READ grant: reads work, writes and user-only endpoints do not
        Grant read = grant(tenant, operatorId, false, "APPROVED", now.minusSeconds(60), now.plusSeconds(3600),
                owner.userId());
        TestSupportGrants.put(read);
        mvc.perform(get("/api/v1/core/legal-entities").with(operator(tenant, operatorId, read.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].code", contains("LE")));
        mvc.perform(post("/api/v1/core/org-unit-types").with(operator(tenant, operatorId, read.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"XX\",\"nameVi\":\"x\",\"nameEn\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/core/me").with(operator(tenant, operatorId, read.id())))
                .andExpect(status().isForbidden());

        // approved READ_WRITE grant: the change is attributed to the operator and the grant in the row audit;
        // privileged permissions (role grants) and the tenant's oversight screen stay out of reach
        Grant write = grant(tenant, operatorId, true, "ACTIVE", now.minusSeconds(60), now.plusSeconds(3600),
                owner.userId());
        TestSupportGrants.put(write);
        mvc.perform(post("/api/v1/core/org-unit-types").with(operator(tenant, operatorId, write.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SUPPORT_FIX\",\"nameVi\":\"Sửa lỗi\",\"nameEn\":\"Fix\"}"))
                .andExpect(status().isCreated());
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event
                 where tenant_id = ? and object_type = 'org_unit_type' and action = 'INSERT'
                   and actor_type = 'OPERATOR' and operator_id = ? and support_grant_id = ?""",
                Integer.class, tenant, operatorId, write.id())).isEqualTo(1);
        mvc.perform(post("/api/v1/core/users/" + owner.userId() + "/role-assignments")
                        .with(operator(tenant, operatorId, write.id())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"" + UUID.randomUUID() + "\",\"scopeType\":\"TENANT\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/core/support-sessions").with(operator(tenant, operatorId, write.id())))
                .andExpect(status().isForbidden());

        // the tenant sees every session and what happened in it
        mvc.perform(get("/api/v1/core/support-sessions").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.supportGrantId == '" + read.id() + "')].requests").value(3))
                .andExpect(jsonPath("$[?(@.supportGrantId == '" + expired.id() + "')].refusedRequests").value(1))
                .andExpect(jsonPath("$[?(@.supportGrantId == '" + write.id() + "')].changes").value(1));
        mvc.perform(get("/api/v1/core/support-sessions/" + read.id() + "/events").with(as(owner)))
                .andExpect(jsonPath("$[0].action").value("SUPPORT_ACCESS"))
                .andExpect(jsonPath("$[0].details.path").value("/api/v1/core/legal-entities"));
    }
}
