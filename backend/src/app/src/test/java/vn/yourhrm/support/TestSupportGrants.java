package vn.yourhrm.support;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.yourhrm.shared.auth.api.spi.SupportGrants;

/** Stand-in for the SaaS support-grant registry (tests only); grants are keyed by id, so tests never collide. */
public class TestSupportGrants implements SupportGrants {

    private static final Map<UUID, Grant> GRANTS = new ConcurrentHashMap<>();

    public static void put(Grant grant) {
        GRANTS.put(grant.id(), grant);
    }

    @Override
    public Optional<Grant> find(UUID tenantId, UUID grantId) {
        return Optional.ofNullable(GRANTS.get(grantId));
    }
}
