package vn.yourhrm.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Base of all integration tests: one Spring context (cached across test classes) against the
 * Testcontainers PostgreSQL with the DDL baseline, connected as yhrm_app_core. JWTs are built with
 * spring-security-test ({@code jwt()}), so no Keycloak is needed; claims are {@code sub} = identity id
 * and {@code tenant_id}.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/unused-in-tests",
        "spring.datasource.hikari.maximum-pool-size=5",
        "yhrm.authz.cache-ttl=60s"
})
@AutoConfigureMockMvc
@Import(RecordingDataSourceConfig.class)
public abstract class IntegrationTest {

    protected static final JdbcTemplate ADMIN = PostgresTestDatabase.admin();
    protected static final TestFixtures FIX = new TestFixtures(ADMIN);

    @Autowired
    protected MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresTestDatabase.APP_ROLE);
        registry.add("spring.datasource.password", () -> PostgresTestDatabase.APP_PASSWORD);
    }

    /** A bearer token of {@code user} (subject = identity, tenant_id claim = user's tenant). */
    protected static RequestPostProcessor as(TestUser user) {
        return jwt().jwt(j -> j.subject(user.identityId().toString()).claim("tenant_id", user.tenantId().toString()));
    }

}
