package vn.yourhrm.support;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.yourhrm.shared.common.storage.ObjectStorage;

/**
 * Stand-in for the SaaS object storage (tests only): bytes in memory, keys checked against the tenant prefix, signed
 * URLs that only carry the key and expiry. Scans are reported by the tests through {@code DocumentScanResults}.
 */
public class TestObjectStorage implements ObjectStorage {

    private static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> SCAN_QUEUED = new ConcurrentHashMap<>();
    public static volatile Duration lastTtl;

    public static boolean contains(String key) {
        return OBJECTS.containsKey(key);
    }

    public static boolean scanQueued(String key) {
        return SCAN_QUEUED.getOrDefault(key, false);
    }

    @Override
    public void put(UUID tenantId, String objectKey, String contentType, byte[] content, boolean scan) {
        if (!objectKey.startsWith(tenantId + "/")) {
            throw new IllegalArgumentException("Key outside the tenant prefix: " + objectKey);
        }
        OBJECTS.put(objectKey, content.clone());
        SCAN_QUEUED.put(objectKey, scan);
    }

    @Override
    public byte[] get(UUID tenantId, String objectKey) {
        if (!objectKey.startsWith(tenantId + "/")) {
            throw new IllegalArgumentException("Key outside the tenant prefix: " + objectKey);
        }
        byte[] b = OBJECTS.get(objectKey);
        if (b == null) {
            throw new IllegalStateException("No object " + objectKey);
        }
        return b.clone();
    }

    @Override
    public URI signedDownloadUrl(UUID tenantId, String objectKey, String fileName, String contentType, Duration ttl) {
        lastTtl = ttl;
        return URI.create("https://storage.test/" + objectKey + "?expires=" + Instant.now().plus(ttl).getEpochSecond());
    }
}
