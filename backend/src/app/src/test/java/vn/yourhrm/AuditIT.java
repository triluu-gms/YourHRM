package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.AuditLog.SecurityEvent;
import vn.yourhrm.core.api.AuditLog.SecurityEventType;
import vn.yourhrm.core.internal.application.AuditSealer;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PostgresTestDatabase;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;

/** The tenant audit store (BR-CORE-090..093, US-CORE-090..093). */
class AuditIT extends IntegrationTest {

    @Autowired
    AuditLog auditLog;

    @Autowired
    AuditSealer sealer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private static <T> T as(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", "vi-VN"), call);
    }

    /** A connection of the sealer role (the only role that may write the seal columns). */
    private static JdbcTemplate sealerConnection() {
        var c = PostgresTestDatabase.container();
        SingleConnectionDataSource ds = new SingleConnectionDataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword(),
                true);
        JdbcTemplate t = new JdbcTemplate(ds);
        t.execute("set role yhrm_audit_sealer");
        return t;
    }

    @Test
    void rowAuditRecordsChannelClientAndCorrelationOfTheRequest() throws Exception {
        UUID tenant = FIX.tenant("au-origin");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        String body = mvc.perform(post("/api/v1/core/cost-centres").with(as(owner))
                        .header("User-Agent", "Mozilla/5.0 (YourHRM test)").header("X-Correlation-Id", "corr-42")
                        .with(r -> {
                            r.setRemoteAddr("203.0.113.7");
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalEntityId\":\"" + le + "\",\"code\":\"CC1\",\"name\":\"Kế toán\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID cc = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
        Map<String, Object> row = ADMIN.queryForMap("""
                select actor_type, actor_id, channel, host(client_ip) as ip, user_agent, correlation_id, change::text as change
                  from audit.audit_event where tenant_id = ? and object_type = 'cost_centre' and object_id = ? and action = 'INSERT'""",
                tenant, cc);
        assertThat(row).containsEntry("actor_type", "USER").containsEntry("actor_id", owner.userId())
                .containsEntry("channel", "UI").containsEntry("ip", "203.0.113.7")
                .containsEntry("user_agent", "Mozilla/5.0 (YourHRM test)").containsEntry("correlation_id", "corr-42");
        assertThat((String) row.get("change")).contains("\"code\": {\"new\": \"CC1\"");

        // work without a request (a job) is SYSTEM
        as(owner, () -> new TransactionTemplate(txManager).execute(s -> {
            auditLog.record(new BusinessAction("APPROVE", "pay", "payroll_run", UUID.randomUUID(), le,
                    Map.of("period", "2026-10")));
            return null;
        }));
        assertThat(ADMIN.queryForObject("select channel from audit.audit_event where tenant_id = ? and action = 'APPROVE'",
                String.class, tenant)).isEqualTo("SYSTEM");
        assertThatThrownBy(() -> as(owner, () -> {
            auditLog.record(new BusinessAction("UPDATE", "pay", "payroll_run", null, null, Map.of()));
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining("row-audited");
    }

    @Test
    void searchFiltersMasksSensitiveValuesAndIsItselfAudited() throws Exception {
        UUID tenant = FIX.tenant("au-search");
        TestUser owner = FIX.owner(tenant);
        UUID role = FIX.role(tenant, "AUDITOR", false, "core.audit.view");
        TestUser auditor = FIX.user(tenant, "auditor@test.local");
        FIX.assign(auditor, role, "TENANT", List.of(), true, TestFixtures.EPOCH, null);
        UUID job = UUID.randomUUID();
        // a row-audit diff of a column classified pii:P2 (customer control totals)
        ADMIN.update("""
                insert into audit.audit_event (tenant_id, actor_type, actor_id, action, object_schema, object_type,
                       object_id, change, channel, client_ip)
                values (?, 'USER', ?, 'UPDATE', 'core', 'import_job', ?,
                        '{"control_totals": {"old": null, "new": [{"check": "YTD_GROSS", "value": 123456789}]},
                          "status": {"old": "PARSED", "new": "VALIDATED"}}', 'IMPORT', '10.0.0.5')""",
                tenant, owner.userId(), job);

        mvc.perform(get("/api/v1/core/audit/events").param("objectType", "import_job").param("objectId", job.toString())
                        .with(as(auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].masked").value(true))
                .andExpect(jsonPath("$.items[0].change.control_totals.new").value("<masked>"))
                .andExpect(jsonPath("$.items[0].change.control_totals.pii").value("P2"))
                .andExpect(jsonPath("$.items[0].change.status.new").value("VALIDATED"))
                .andExpect(jsonPath("$.items[0].occurredAtLocal").exists());
        mvc.perform(get("/api/v1/core/audit/events").param("objectId", job.toString()).with(as(owner)))
                .andExpect(jsonPath("$.items[0].masked").value(false))
                .andExpect(jsonPath("$.items[0].change.control_totals.new[0].value").value(123456789));
        mvc.perform(get("/api/v1/core/audit/events").param("module", "core").param("channel", "IMPORT")
                        .param("clientIp", "10.0.0.5").param("userId", owner.userId().toString()).with(as(auditor)))
                .andExpect(jsonPath("$.items[*].objectId", everyItem(org.hamcrest.Matchers.is(job.toString()))));
        mvc.perform(get("/api/v1/core/audit/events").param("from", "2020-01-01T00:00:00Z").with(as(auditor)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("range-too-long"));
        mvc.perform(get("/api/v1/core/audit/events").param("clientIp", "not-an-ip").with(as(auditor)))
                .andExpect(status().isUnprocessableContent());

        // every search is audited, with whether sensitive values were shown
        assertThat(ADMIN.queryForList("""
                select is_sensitive_read from audit.audit_event where tenant_id = ? and action = 'AUDIT_SEARCH'
                 order by occurred_at""", Boolean.class, tenant)).containsExactly(false, true, false);
        TestUser nobody = FIX.user(tenant, "nobody@test.local");
        mvc.perform(get("/api/v1/core/audit/events").with(as(nobody))).andExpect(status().isForbidden());
    }

    @Test
    void securityEventsSurviveARolledBackRequestAndAreFilteredAsSecurity() throws Exception {
        UUID tenant = FIX.tenant("au-sec");
        TestUser owner = FIX.owner(tenant);
        TestUser user = FIX.user(tenant, "user@test.local");
        // the SaaS identity layer reports a failed login inside a request that then fails
        assertThatThrownBy(() -> as(owner, () -> new TransactionTemplate(txManager).execute(s -> {
            auditLog.recordSecurityEvent(new SecurityEvent(tenant, SecurityEventType.LOGIN_FAILED, user.userId(),
                    user.login(), null, "198.51.100.9", "Keycloak", Map.of("reason", "bad-password")));
            throw new IllegalStateException("request failed");
        }))).hasMessageContaining("request failed");
        // P-27 without any tenant context (SaaS back-office)
        auditLog.recordSecurityEvent(new SecurityEvent(tenant, SecurityEventType.BREAK_GLASS_STARTED, null, null,
                UUID.randomUUID(), "198.51.100.10", null, Map.of("ticket", "SUP-1")));

        mvc.perform(get("/api/v1/core/audit/events").param("security", "true").with(as(owner)))
                .andExpect(jsonPath("$.items[*].action", hasItem("LOGIN_FAILED")))
                .andExpect(jsonPath("$.items[*].action", hasItem("BREAK_GLASS_STARTED")))
                .andExpect(jsonPath("$.items[?(@.action == 'LOGIN_FAILED')].clientIp").value(hasItem("198.51.100.9")))
                .andExpect(jsonPath("$.items[?(@.action == 'BREAK_GLASS_STARTED')].actorType").value(hasItem("OPERATOR")));
        // role changes are security events too (row audit of the role grants)
        mvc.perform(post("/api/v1/core/roles").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HR\",\"nameVi\":\"Nhân sự\",\"nameEn\":\"HR\",\"permissions\":[{\"code\":\"core.user.view\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/core/audit/events").param("security", "true").with(as(owner)))
                .andExpect(jsonPath("$.items[*].objectType", hasItem("role_permission")));
    }

    @Test
    void theChainIsSealedVerifiedAndTamperingIsDetected() throws Exception {
        UUID tenant = FIX.tenant("au-chain");
        TestUser owner = FIX.owner(tenant);
        // concurrent writers append without waiting for each other
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Object>> writes = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            writes.add(pool.submit(() -> as(owner, () -> new TransactionTemplate(txManager).execute(s -> {
                auditLog.record(new BusinessAction("GENERATE", "hr", "document", UUID.randomUUID(), null,
                        Map.of("n", String.valueOf(n))));
                return null;
            }))));
        }
        for (Future<Object> f : writes) {
            f.get();
        }
        pool.shutdown();
        Thread.sleep(2_500); // the sealer takes rows older than 2 s (ADR-0027)
        JdbcTemplate sealerDb = sealerConnection();
        assertThat(sealer.sealPending(sealerDb)).isGreaterThanOrEqualTo(20);
        assertThat(sealer.anchor(sealerDb, LocalDate.now())).isPositive();

        mvc.perform(post("/api/v1/core/audit/chain/verify").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.lastSealedSeq").value(org.hamcrest.Matchers.greaterThanOrEqualTo(20)))
                .andExpect(jsonPath("$.anchorsChecked").value(1));

        // the application role can append but never change or delete an event
        assertThatThrownBy(() -> as(owner, () -> new TransactionTemplate(txManager).execute(s ->
                jdbc.sql("update audit.audit_event set action = 'X' where tenant_id = :t").param("t", tenant).update())))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> as(owner, () -> new TransactionTemplate(txManager).execute(s ->
                jdbc.sql("delete from audit.audit_event where tenant_id = :t").param("t", tenant).update())))
                .rootCause().hasMessageContaining("permission denied");
        // the sealer may write seal columns only, and never a sealed row
        assertThatThrownBy(() -> sealerDb.update("update audit.audit_event set sealed_at = now() where tenant_id = ?", tenant))
                .hasMessageContaining("sealed");

        // someone with database superpowers edits a sealed event: the chain breaks at its sequence number
        long seq = ADMIN.queryForObject("select chain_seq from audit.audit_event where tenant_id = ? and chain_seq = 5",
                Long.class, tenant);
        var c = PostgresTestDatabase.container();
        JdbcTemplate rogue = new JdbcTemplate(new SingleConnectionDataSource(c.getJdbcUrl(), c.getUsername(),
                c.getPassword(), true));
        rogue.execute("set session_replication_role = replica");
        rogue.update("update audit.audit_event set change = '{\"n\": \"999\"}' where tenant_id = ? and chain_seq = ?",
                tenant, seq);
        mvc.perform(post("/api/v1/core/audit/chain/verify").with(as(owner)))
                .andExpect(jsonPath("$.intact").value(false))
                .andExpect(jsonPath("$.firstBrokenSeq").value(5));
        // a deleted event leaves a gap
        rogue.update("delete from audit.audit_event where tenant_id = ? and chain_seq = 3", tenant);
        mvc.perform(post("/api/v1/core/audit/chain/verify").param("fromSeq", "1").param("toSeq", "4").with(as(owner)))
                .andExpect(jsonPath("$.firstBrokenSeq").value(3));
        assertThat(ADMIN.queryForObject("select count(*) from audit.audit_event where tenant_id = ? and action = 'AUDIT_VERIFY'",
                Integer.class, tenant)).isEqualTo(3);
    }
}
