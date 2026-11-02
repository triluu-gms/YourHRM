package vn.yourhrm.support;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.yourhrm.shared.common.legal.LegalParameters;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Stand-in for the SaaS Legal Parameter Service (tests only): the admin unit → region map per pack version and
 * LOCATION-scoped overrides, held per tenant so test classes sharing the Spring context never interfere. Values
 * are not dated (a pack's map applies on every date), which is enough for Core's tests.
 *
 * <p>Scalar / structured parameters ({@link #value}): platform-wide defaults ({@link #define}, e.g. People's
 * statutory examples registered by a test fixture) and per-tenant values ({@link #define(UUID, String, Object)})
 * that win over them. Every value is reported as coming from {@link #PACK_ID} version 1.
 */
public class TestLegalParameters implements LegalParameters {

    private static final class Packs {
        long current = 1;
        final Map<Long, Map<String, String>> regionMaps = new HashMap<>();
        final Map<UUID, String> locationOverrides = new HashMap<>();
    }

    private static final Map<UUID, Packs> TENANTS = new ConcurrentHashMap<>();
    private static final Map<String, Object> DEFAULTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Object>> TENANT_VALUES = new ConcurrentHashMap<>();

    /** The pretend published pack all scalar values come from. */
    public static final UUID PACK_ID = UUID.fromString("00000000-0000-7000-8000-00000000a001");

    /** Defines a platform-wide value of {@code key} (all tenants, every date). */
    public static void define(String key, Object value) {
        DEFAULTS.put(key, value);
    }

    /** Defines {@code key} for one tenant only; wins over {@link #define(String, Object)}. */
    public static void define(UUID tenant, String key, Object value) {
        TENANT_VALUES.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>()).put(key, value);
    }

    /** Removes a tenant value of {@code key}, e.g. to test a missing parameter. */
    public static void undefine(UUID tenant, String key) {
        TENANT_VALUES.getOrDefault(tenant, Map.of()).remove(key);
    }

    @Override
    public Optional<Value> value(String key, Scope scope, LocalDate asOf) {
        Map<String, Object> own = TENANT_VALUES.getOrDefault(TenantContext.current().tenantId(), Map.of());
        if (own.containsKey(key)) {
            return Optional.of(new Value(own.get(key), PACK_ID, 1, true));
        }
        return Optional.ofNullable(DEFAULTS.get(key)).map(v -> new Value(v, PACK_ID, 1, false));
    }

    /** Defines (or replaces) the region map of {@code packVersion} and makes it the current pack. */
    public static synchronized void publish(UUID tenant, long packVersion, Map<String, String> adminUnitToRegion) {
        Packs p = TENANTS.computeIfAbsent(tenant, t -> new Packs());
        p.regionMaps.put(packVersion, Map.copyOf(adminUnitToRegion));
        p.current = packVersion;
    }

    public static synchronized void overrideLocation(UUID tenant, UUID location, String region) {
        TENANTS.computeIfAbsent(tenant, t -> new Packs()).locationOverrides.put(location, region);
    }

    @Override
    public Optional<MapEntry> mapEntry(String key, String rowKey, Scope scope, LocalDate asOf) {
        Packs p = TENANTS.get(TenantContext.current().tenantId());
        return p == null ? Optional.empty() : mapEntry(key, rowKey, scope, asOf, p.current);
    }

    @Override
    public synchronized Optional<MapEntry> mapEntry(String key, String rowKey, Scope scope, LocalDate asOf,
            long packVersion) {
        Packs p = TENANTS.get(TenantContext.current().tenantId());
        if (p == null || !MIN_WAGE_REGION_MAP.equals(key)) {
            return Optional.empty();
        }
        if (scope.locationId() != null && p.locationOverrides.containsKey(scope.locationId())) {
            return Optional.of(new MapEntry(Map.of("region", p.locationOverrides.get(scope.locationId())), packVersion,
                    true));
        }
        String region = p.regionMaps.getOrDefault(packVersion, Map.of()).get(rowKey);
        return region == null ? Optional.empty() : Optional.of(new MapEntry(Map.of("region", region), packVersion, false));
    }
}
