package vn.yourhrm.support;

import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.yourhrm.shared.common.entitlement.TenantLimits;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Stand-in for the SaaS entitlement resolver (tests only): limits are set per tenant, so tests sharing the
 * Spring context never see each other's plan. Unset = no limit.
 */
public class TestTenantLimits implements TenantLimits {

    private static final Map<UUID, Map<String, Long>> LIMITS = new ConcurrentHashMap<>();

    public static void set(UUID tenant, String key, long value) {
        LIMITS.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    public OptionalLong limit(String key) {
        Long value = LIMITS.getOrDefault(TenantContext.current().tenantId(), Map.of()).get(key);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
