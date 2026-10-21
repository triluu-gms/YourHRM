package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.core.internal.application.AuthorisationCache;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * US-CORE-038 smoke benchmark (the real load test with 10k employees / 2k units is TECH:perf):
 * a cached check must stay far below 20 ms; a cold computation (snapshot + subtree SQL) is measured too.
 */
class AuthorisationPerformanceIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AuthorisationPerformanceIT.class);

    @Autowired
    AuthorizationService authorization;

    @Autowired
    AuthorisationCache cache;

    @Test
    void checksMeetTheLatencyBudget() throws Exception {
        UUID tenant = FIX.tenant("perf");
        UUID le = FIX.legalEntity(tenant, "LE");
        OrgUnit root = FIX.orgUnit(tenant, "R", null, le, null, TestFixtures.EPOCH, null);
        List<UUID> units = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            OrgUnit dept = FIX.orgUnit(tenant, "D" + i, root, le, null, TestFixtures.EPOCH, null);
            for (int j = 0; j < 10; j++) {
                units.add(FIX.orgUnit(tenant, "D" + i + "T" + j, dept, le, null, TestFixtures.EPOCH, null).id());
            }
        }
        UUID role = FIX.role(tenant, "PERF", false, "core.org_unit.view");
        TestUser user = FIX.user(tenant, "perf@test.local");
        FIX.assign(user, role, "ORG_UNIT", List.of(root.id()), true, TestFixtures.EPOCH, null);
        TenantContext ctx = new TenantContext(tenant, user.userId(), user.identityId().toString(), "perf", "vi-VN");
        LocalDate today = LocalDate.now();

        TenantContext.callWith(ctx, () -> {
            // cold: evict, then snapshot load + ltree subtree expansion (221 units)
            List<Long> cold = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                cache.evictUser(tenant, user.userId());
                long t0 = System.nanoTime();
                assertThat(authorization.can("core.org_unit.view", new AuthorizationTarget.OrgUnit(units.get(i % units.size()))))
                        .isTrue();
                cold.add(System.nanoTime() - t0);
            }
            // warm: served from the cache
            List<Long> warm = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) {
                UUID unit = units.get(i % units.size());
                long t0 = System.nanoTime();
                assertThat(authorization.can("core.org_unit.view", new AuthorizationTarget.OrgUnit(unit), today)).isTrue();
                warm.add(System.nanoTime() - t0);
            }
            double coldP95 = p95(cold.subList(10, cold.size())); // first iterations include JIT warm-up
            double warmP95 = p95(warm.subList(500, warm.size()));
            log.info("authorisation check latency: cold p95 = {} ms, warm p95 = {} ms", coldP95, warmP95);
            // NFR-CORE-002 budget (<= 20 ms per request) is met by the cached path with a wide margin.
            assertThat(warmP95).isLessThan(1.0);
            // Cold compute (3-4 SQL round trips) measures ~13-18 ms against Docker Desktop on a laptop; the
            // binding cold budget is measured on server hardware in TECH:perf / SP-2, so only a sanity bound here.
            assertThat(coldP95).isLessThan(50.0);
            return null;
        });
    }

    private static double p95(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) / 1_000_000.0;
    }
}
