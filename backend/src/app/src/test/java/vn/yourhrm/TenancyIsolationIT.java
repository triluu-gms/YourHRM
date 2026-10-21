package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.shared.common.tenancy.NoTenantContextException;
import vn.yourhrm.shared.common.tenancy.TenantAwareJpaTransactionManager;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PostgresTestDatabase;
import vn.yourhrm.support.StatementRecorder;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Tenancy per transaction (DESIGN.md §3.2, ADR-0003): tenant from the JWT only, set_config(...) as the
 * first statement of every transaction, RLS fail-closed outside a transaction / context.
 */
class TenancyIsolationIT extends IntegrationTest {

    static UUID tenantA;
    static UUID tenantB;
    static TestUser ownerA;
    static TestUser ownerB;
    static UUID roleOfB;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @BeforeAll
    static void data() {
        tenantA = FIX.tenant("iso-a");
        tenantB = FIX.tenant("iso-b");
        ownerA = FIX.owner(tenantA);
        ownerB = FIX.owner(tenantB);
        roleOfB = FIX.role(tenantB, "B_ONLY_ROLE", false, "core.role.view");
        FIX.user(tenantB, "only-in-b@test.local");
    }

    @Test
    void usesTheTenantAwareTransactionManager() {
        assertThat(txManager).isInstanceOf(TenantAwareJpaTransactionManager.class);
    }

    @Test
    void tenantsSeeOnlyTheirOwnRolesAndUsersThroughTheApi() throws Exception {
        mvc.perform(get("/api/v1/core/roles").with(as(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("TENANT_OWNER")))
                .andExpect(jsonPath("$[*].code", not(hasItem("B_ONLY_ROLE"))));
        mvc.perform(get("/api/v1/core/roles").with(as(ownerB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("B_ONLY_ROLE")));

        mvc.perform(get("/api/v1/core/users").param("size", "200").with(as(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].login", hasItem(ownerA.login())))
                .andExpect(jsonPath("$.items[*].login", not(hasItem("only-in-b@test.local"))))
                .andExpect(jsonPath("$.items[*].login", not(hasItem(ownerB.login()))));

        // a known id of tenant B is simply not found from tenant A
        mvc.perform(get("/api/v1/core/roles/" + roleOfB).with(as(ownerA))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/core/users/" + ownerB.userId()).with(as(ownerA))).andExpect(status().isNotFound());
    }

    @Test
    void identityOfAnotherTenantIsRejected() throws Exception {
        // owner B's identity presented with tenant A's claim: no such user in tenant A -> 403
        TestUser forged = new TestUser(tenantA, ownerB.userId(), ownerB.identityId(), ownerB.login());
        mvc.perform(get("/api/v1/core/me").with(as(forged)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("unknown-user"));
    }

    @Test
    void tenantHeaderIsIgnored() throws Exception {
        mvc.perform(get("/api/v1/core/roles").with(as(ownerA)).header("X-Tenant-Id", tenantB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", not(hasItem("B_ONLY_ROLE"))));
    }

    @Test
    void writesCannotTargetAnotherTenant() throws Exception {
        String body = """
                {"roleId":"%s","scopeType":"TENANT"}""".formatted(roleOfB);
        mvc.perform(post("/api/v1/core/users/" + ownerA.userId() + "/role-assignments").with(as(ownerA))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown-role"));
    }

    @Test
    void queriesOutsideATransactionSeeNothing() {
        Integer adminCount = ADMIN.queryForObject("select count(*) from core.role", Integer.class);
        assertThat(adminCount).isPositive();
        // application DataSource, no transaction => no tenant context => RLS returns no rows
        assertThat(jdbc.sql("select count(*) from core.role").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from core.user_account").query(Long.class).single()).isZero();
        // and writes are rejected by the RLS WITH CHECK
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into core.role (tenant_id, id, code, name_vi, name_en)
                        values (:t, gen_random_uuid(), 'X', 'x', 'x')""").param("t", tenantA).update())
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void transactionWithoutTenantContextIsRefused() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.execute(s -> jdbc.sql("select 1").query(Integer.class).single()))
                .isInstanceOf(NoTenantContextException.class);
    }

    @Test
    void transactionWithTenantContextSeesOnlyThatTenant() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        List<String> codes = TenantContext.callWith(new TenantContext(tenantB, null, null, "t", null),
                () -> tx.execute(s -> jdbc.sql("select code from core.role order by code").query(String.class).list()));
        assertThat(codes).contains("B_ONLY_ROLE", "TENANT_OWNER").hasSize(2);
        Long count = TenantContext.callWith(new TenantContext(tenantB, null, null, "t", null),
                () -> tx.execute(s -> jdbc.sql("select count(*) from core.role where tenant_id = :a")
                        .param("a", tenantA).query(Long.class).single()));
        assertThat(count).isZero();
    }

    @Test
    void contextIsTransactionLocalOnAPooledConnection() throws Exception {
        try (Connection c = DriverManager.getConnection(PostgresTestDatabase.jdbcUrl(), PostgresTestDatabase.APP_ROLE,
                PostgresTestDatabase.APP_PASSWORD); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("select set_config('app.tenant_id', '" + tenantA + "', true)");
            assertThat(count(st, "select count(*) from core.role")).isPositive();
            c.commit();
            c.setAutoCommit(true);
            // same session, after commit: the transaction-local setting is gone (fail closed)
            assertThat(count(st, "select count(*) from core.role")).isZero();
        }
    }

    @Test
    void setConfigIsTheFirstStatementOfEveryTransaction() throws Exception {
        StatementRecorder.clear();
        mvc.perform(get("/api/v1/core/roles").with(as(ownerA))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/core/me").with(as(ownerA))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/core/users").with(as(ownerA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"first-stmt-" + UUID.randomUUID() + "@test.local\"}"))
                .andExpect(status().isCreated());
        List<String> first = StatementRecorder.firstStatements();
        assertThat(first).hasSizeGreaterThanOrEqualTo(3);
        assertThat(first).allSatisfy(sql -> assertThat(sql).isEqualTo(TenantAwareJpaTransactionManager.SET_CONTEXT_SQL));
    }

    @Test
    void responsesOfTenantAContainNoTenantBIds() throws Exception {
        mvc.perform(get("/api/v1/core/roles").with(as(ownerA)))
                .andExpect(jsonPath("$[*].id", everyItem(not(roleOfB.toString()))));
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
