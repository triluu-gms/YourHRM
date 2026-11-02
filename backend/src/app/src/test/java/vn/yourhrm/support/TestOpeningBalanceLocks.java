package vn.yourhrm.support;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.yourhrm.shared.common.onboarding.OpeningBalanceLocks;

/** Stand-in for the SaaS go-live lock (tests only): tenants locked by the test. */
public class TestOpeningBalanceLocks implements OpeningBalanceLocks {

    private static final Set<UUID> LOCKED = ConcurrentHashMap.newKeySet();

    public static void lock(UUID tenant) {
        LOCKED.add(tenant);
    }

    @Override
    public boolean isLocked(UUID tenantId, UUID legalEntityId) {
        return LOCKED.contains(tenantId);
    }
}
